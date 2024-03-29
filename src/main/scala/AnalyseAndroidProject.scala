import javassist._
import org.opalj.ai.{AIResult, ValueOriginsIterator, ValuesDomain}
import org.opalj.ai.domain.{PerformAI, RefineDefUseUsingOrigins}
import org.opalj.ai.domain.l1.DefaultDomainWithCFGAndDefUse
import org.opalj.br.analyses.Project
import org.opalj.br.instructions._
import org.opalj.br._
import org.opalj.collection.immutable.{IntTrieSet, Naught}

import java.net.URL
import scala.collection.mutable.ListBuffer


class AnalyseAndroidProject(projectJAR:String) {
  var project: Project[URL] = Project(new java.io.File(projectJAR))
  val reflectPackageName = "java.lang.reflect"
  var methodCallReflectionInvoke: ListBuffer[Method] = new ListBuffer[Method]()
  var methodCallReflectionSet: ListBuffer[Method] = new ListBuffer[Method]()
  var methodCallReflectionSetAccessible: ListBuffer[Method] = new ListBuffer[Method]()
  var setAccessibleObjects: ListBuffer[ReflectionSetAccessible] = new ListBuffer[ReflectionSetAccessible]()
  var setObjects: ListBuffer[ReflectionSet] = new ListBuffer[ReflectionSet]()
  var invokeObjects: ListBuffer[ReflectionInvoke] = new ListBuffer[ReflectionInvoke]()
  var countInvoke = 0
  var countSetAccessible = 0
  var countSet = 0

  def setMethodCallReflection(method: Method, project: Project[URL]): Unit = {
    if (method.body.isDefined) {
      val body = method.body.get
      val domain = new DefaultDomainWithCFGAndDefUse(project, method) with RefineDefUseUsingOrigins
      val result: AIResult {val domain: DefaultDomainWithCFGAndDefUse[URL]} = PerformAI(domain)
      body.iterate { (pc, instr) =>
        if (instr.isInvocationInstruction && checkInstructionCallReflection(instr)) {
          var byteCodeInfo = new ByteCodeInfo()
          val name = instr.asInvocationInstruction.name
          if (name.equals("invoke")) {
            var obj = new ReflectionInvoke()
            makeReflectionUseObject(pc, instr, method, obj, byteCodeInfo)
            methodCallReflectionInvoke += method
            if (invokeObjects.length == 63)
              println(invokeObjects.length)
            getInfosFromInvoke(pc, body, obj, byteCodeInfo, result)
            if (obj.isValid){
              countInvoke += 1
            }
            invokeObjects += obj
          }
          else if (name.equals("setAccessible")) {
            methodCallReflectionSetAccessible += method
            var obj = new ReflectionSetAccessible()
            makeReflectionUseObject(pc, instr, method, obj, byteCodeInfo)
            getInfosFromSetAccessible(pc, body, obj, byteCodeInfo, result)
            if (obj.isValid){
              countSetAccessible += 1
            }
            setAccessibleObjects += obj
          }
          else if (name.startsWith("set")) {
            var obj = new ReflectionSet()
            makeReflectionUseObject(pc, instr, method, obj, byteCodeInfo)
            methodCallReflectionSet += method
            getInfosFromSet(pc, body, obj, byteCodeInfo, result)
            if (obj.isValid){
              countSet += 1
            }
            setObjects += obj
          }
        }
      }
    }
  }

  def makeReflectionUseObject(pc: Int, instruction: Instruction, method: Method, obj: ReflectionUse, byteCodeInfo: ByteCodeInfo): Unit = {
    obj.className = method.classFile.fqn
    obj.method = method
    val name = instruction.asInvocationInstruction.name
    obj.nameReflectionFunction = Option(name)
    byteCodeInfo.setInfo(pc, instruction)
    obj.byteCodeInfo = Option(byteCodeInfo)
  }


  def getInfosFromSetAccessible(pc: Integer, body: Code, obj: ReflectionSetAccessible, byteCodeInfo: ByteCodeInfo, result: AIResult {val domain: DefaultDomainWithCFGAndDefUse[URL]}): Unit = {
    val operands = result.operandsArray(pc)
    if (operands == null) {
      return
    }
    var new_info = new ByteCodeInfo()
    byteCodeInfo.previousByteCodeInfo = Option(new_info)
    var op = operands.last
    op match {
      case result.domain.MultipleReferenceValues(v) =>
        obj.isValid = false
      case result.domain.DomainReferenceValueTag(v) =>
        if (v.allValues.exists(p =>
          p.upperTypeBound.containsId(ObjectType("java/lang/reflect/Field").id))) {
          result.domain.originsIterator(op).foreach(origin => {
            setFieldToObject(origin, obj, new_info, body, result)
          })
        }
        else if (v.allValues.exists(p =>
          p.upperTypeBound.containsId(ObjectType("java/lang/reflect/Method").id))) {
          result.domain.originsIterator(op).foreach(origin => {
            setMethodToObject(origin, obj, new_info, body, result)
          })
        }
        else if (v.allValues.exists(p =>
          p.upperTypeBound.containsId(ObjectType("java/lang/reflect/Constructor").id))) {
          result.domain.originsIterator(op).foreach(origin => {
            setConstructorToObject(origin, obj, new_info, body, result)
          })
        }
      case e ⇒
    }
  }

  def getInfosFromInvoke(pc: Integer, body: Code, obj: ReflectionInvoke, byteCodeInfo: ByteCodeInfo, result: AIResult {val domain: DefaultDomainWithCFGAndDefUse[URL]}): Unit = {
    val operands = result.operandsArray(pc)
    if (operands == null) {
      return
    }
    var new_info = new ByteCodeInfo()
    byteCodeInfo.previousByteCodeInfo = Option(new_info)
    result.domain.originsIterator(operands.last).foreach(org => {
      setMethodToObject(org, obj, new_info, body, result)
    })
    var lst = new ListBuffer[ByteCodeInfo]()
    byteCodeInfo.parametersByteCodeInfo = Option(lst)
    var iter = operands.toIterator
    var op = iter.next()
    op match {
      case result.domain.DomainInitializedArrayValueTag(v) =>
        if (v.theLength > 0){
          var byteCodeInfoWithArray = new ByteCodeInfoWithArray()
          workWithInstructionANewArray(v.theLength, v.origin, obj, body, byteCodeInfoWithArray, result)
          obj.valueInfo = byteCodeInfoWithArray.values
          lst.append(byteCodeInfoWithArray)
        }
      case result.domain.MultipleReferenceValues(v) =>
        obj.isValid = false
        return
      case result.domain.DomainReferenceValueTag(v) =>
        result.domain.originsIterator(op).foreach(org => {
          var info = new StringBuilder()
          var n_info = new ByteCodeInfo()
          lst.append(n_info)
          getInfoParameterAsObject(org, body, obj, n_info, info, result)
          obj.valueInfo.append(n_info)
        })
      case _ =>
    }
    op = iter.next()
    while (iter.hasNext && !op.equals(operands.last)){
      var n_info = new ByteCodeInfo()
      op match {
        case result.domain.MultipleReferenceValues(v) =>
          obj.isValid = false
          setMultipleByteCodeInfo(v.idSet, obj, n_info, body, result)
          lst.append(n_info)
        case result.domain.DomainReferenceValueTag(v) =>
          result.domain.originsIterator(op).foreach(org => {
            var info = new StringBuilder()
            getInfoParameterAsObject(org, body, obj, n_info, info, result)
            obj.modifiedObject = info.toString()
            obj.objectInfo = n_info
            lst.append(n_info)
          })
        case _ =>
      }
      op = iter.next()
    }

  }

  def getInfosFromSet(pc: Integer, body: Code, obj: ReflectionSet, byteCodeInfo: ByteCodeInfo, result: AIResult {val domain: DefaultDomainWithCFGAndDefUse[URL]}): Unit = {
    val operands = result.operandsArray(pc)
    if (operands == null) {
      return
    }
    var new_info = new ByteCodeInfo()
    byteCodeInfo.previousByteCodeInfo = Option(new_info)
    val value_operand = operands.head
    var lst = new ListBuffer[ByteCodeInfo]()
    byteCodeInfo.parametersByteCodeInfo = Option(lst)
    var value_param = new ByteCodeInfo()
    lst.append(value_param)
    var info = new StringBuilder()
    value_operand match {
      case result.domain.MultipleReferenceValues(v) =>
        setMultipleByteCodeInfo(v.idSet, obj, value_param, body, result)
        obj.isValid = false
      case result.domain.DomainReferenceValueTag(v) =>
        result.domain.originsIterator(value_operand).foreach(org => {
          getInfoParameterAsObject(org, body, obj, value_param, info, result)
          obj.valueObject = info.toString()
          obj.valueInfo = value_param
        })
      case _ =>
        if (value_operand.isInstanceOf[result.domain.AnIntegerValue]) {
          obj.isValid = false
          return
        }
    }
    var object_param = new ByteCodeInfo()
    lst.append(object_param)
    var object_operand = operands(1)
    info = new StringBuilder()
    object_operand match {
      case result.domain.MultipleReferenceValues(v) =>
        obj.isValid = false
        return
      case result.domain.DomainReferenceValueTag(v) =>
        result.domain.originsIterator(value_operand).foreach(org => {
          getInfoParameterAsObject(org, body, obj, object_param, info, result)
          obj.modifiedObject = info.toString()
          obj.objectInfo = object_param
        })
      case _ =>
        if (value_operand.isInstanceOf[result.domain.AnIntegerValue]) {
          obj.isValid = false
          return
        }
    }
    result.domain.originsIterator(operands.last).foreach(org => {
      if (org < 0) {
        obj.isValid = false
        return
      }
      val instr = body.instructions(org)
      if (instr.isInvocationInstruction &&
        (instr.asInvocationInstruction.name.equals("getDeclaredField") || instr.asInvocationInstruction.name.equals("getField")))
        setFieldToObject(org, obj, new_info, body, result)
    }
    )
  }

  def getInfoParameterAsObject(origin: Int, body: Code, obj: ReflectionUse, byteCodeInfo: ByteCodeInfo, info: StringBuilder,
                               result: AIResult {val domain: DefaultDomainWithCFGAndDefUse[URL]}): Unit = {
    byteCodeInfo.pc = origin
    if (origin < 0) {
      checkOriginValue(origin, obj, info)
      return
    }
    val instruction = body.instructions(origin)
    byteCodeInfo.instruction = instruction
    instruction.opcode match {
      case ACONST_NULL.opcode =>
        info.append("null")
      case INVOKESTATIC.opcode =>
        val invoke = instruction.asMethodInvocationInstruction
        val class_name = invoke.declaringClass.toJava
        val method_name = invoke.name
        if (method_name == "valueOf"){
          var new_info = new ByteCodeInfo()
          byteCodeInfo.previousByteCodeInfo = Option(new_info)
          workWithFunctionValueOf(origin, obj, body,new_info, result)
        } else {
          if (class_name.equals(obj.className))
            info.append("this." + method_name)
          else {
            info.append(class_name.split('.').last + "." + method_name)
          }
        }
      case INVOKEVIRTUAL.opcode |
           INVOKESPECIAL.opcode |
           INVOKEINTERFACE.opcode =>
        var valueObject = new StringBuilder()
        objectToString(origin, valueObject, obj, byteCodeInfo, body, result)
        if (obj.isValid)
          info.append(valueObject.toString())
      case GETFIELD.opcode =>
        val get_field = instruction.asInstanceOf[GETFIELD]
        info.append("this." + get_field.name)
      case GETSTATIC.opcode =>
        val get_static = instruction.asInstanceOf[GETSTATIC]
        info.append("this." + get_static.name)
      case NEW.opcode =>
        var next_pc = body.pcOfNextInstruction(origin)
        var instr = body.instructions(next_pc)
        var count = 0
        while (instr.isInstanceOf[INVOKESPECIAL] && instr.asInvocationInstruction.name != "<init>"){
          next_pc = body.pcOfNextInstruction(next_pc)
          instr = body.instructions(next_pc)
          count += 1
        }
        if (count == 1){
          info.append("new " + instruction.asNEW.objectType.simpleName + "()")
        }
        val operands = result.operandsArray(next_pc)
        var lst = new ListBuffer[ByteCodeInfo]()
        byteCodeInfo.parametersByteCodeInfo = Option(lst)
        operands.foreach(op => {
          if(op.equals(operands.last)) return
          op match {
            case result.domain.MultipleReferenceValues(v) =>
              var info = new ByteCodeInfo()
              lst.append(info)
              setMultipleByteCodeInfo(v.idSet, obj, info, body, result)
            case result.domain.DomainReferenceValueTag(v) =>
              var info = new ByteCodeInfo()
              lst.append(info)
              result.domain.originsIterator(op).foreach(org =>
                getAnotherInfos(org, obj, info, body, result)
              )
            case result.domain.DomainInitializedArrayValueTag(v) =>
              if (v.theLength > 0){
                var byteCodeInfoWithArray = new ByteCodeInfoWithArray()
                workWithInstructionANewArray(v.theLength, v.origin, obj, body, byteCodeInfoWithArray, result)
                lst.append(byteCodeInfoWithArray)
              }
            case _ =>
              println("test")
        }
        })
      case _ =>
    }
  }

  def workWithInstructionANewArray(array_length: Int, origin: Int, obj: ReflectionUse, body:Code, info: ByteCodeInfoWithArray, result: AIResult {val domain : DefaultDomainWithCFGAndDefUse[URL]}): Unit = {
    var pc = origin
    var count = 0
    while (count != array_length){
      var new_info = new ByteCodeInfo()
      var instruction = body.instructions(pc)
      pc = body.pcOfNextInstruction(pc)
      instruction = body.instructions(pc)
      var byteCodeInfoOfValueInArray = new ByteCodeInfoOfValueInArray()
      while (instruction.opcode != AASTORE.opcode){
        instruction match {
          case DUP =>
            for (x <- 0 to 1 ){
              pc = body.pcOfNextInstruction(pc)
              instruction = body.instructions(pc)
            }
          case _ =>
            if (instruction.isLoadLocalVariableInstruction){
              var new_bInfo = new ByteCodeInfo()
              val indexOfReadLocal = instruction.asLoadLocalVariableInstruction.indexOfReadLocal
              if(indexOfReadLocal == 0){
                new_bInfo.setInfo(pc, instruction)
              } else {
                findInstructionStoreWithIndex(pc, indexOfReadLocal, obj, body, new_bInfo, result)
              }
              byteCodeInfoOfValueInArray.values.append(new_bInfo)
            } else if (instruction.isInstanceOf[ANEWARRAY]){
              val pre_instruction = body.instructions(body.pcOfPreviousInstruction(pc))
              byteCodeInfoOfValueInArray.values.dropRight(1)
              val a_length = pre_instruction.mnemonic.split('_')(1).toInt
              var byteCodeInfoWithArray = new ByteCodeInfoWithArray()
              byteCodeInfoWithArray.setInfo(pc, instruction)
              byteCodeInfoOfValueInArray.values.append(byteCodeInfoWithArray)
              if (a_length > 0){
                workWithInstructionANewArray(a_length, pc, obj, body, byteCodeInfoWithArray, result)
              }
            } else {
              val n_info = new ByteCodeInfo()
              n_info.setInfo(pc, instruction)
              byteCodeInfoOfValueInArray.values.append(n_info)
            }
            pc = body.pcOfNextInstruction(pc)
            instruction = body.instructions(pc)
        }
        byteCodeInfoOfValueInArray.setInfo(pc, instruction)
      }
      count += 1
      info.values.append(byteCodeInfoOfValueInArray)
    }
  }

  def workWithFunctionValueOf(origin: Int, obj: ReflectionUse, body: Code, info: ByteCodeInfo, result: AIResult {val domain : DefaultDomainWithCFGAndDefUse[URL]}): Unit = {
    val pre_pc = body.pcOfPreviousInstruction(origin)
    val instruction = body.instructions(pre_pc)
    info.setInfo(pre_pc, instruction)
    if (instruction.isLoadConstantInstruction){
      println("no solution")
    } else if (instruction.isLoadLocalVariableInstruction){
      val indexOfReadLocal = instruction.asLoadLocalVariableInstruction.indexOfReadLocal
      findInstructionStoreWithIndex(pre_pc, indexOfReadLocal, obj, body, info, result)
    } else {
      println("no solution")
    }
  }

  def findInstructionStoreWithIndex(origin: Int, index: Int, obj: ReflectionUse, body: Code, byteCodeInfo: ByteCodeInfo, result: AIResult {val domain : DefaultDomainWithCFGAndDefUse[URL]}): Unit = {
    val pre_pc = body.pcOfPreviousInstruction(origin)
    if (pre_pc == 0) {
      obj.isValid = false
      return
    }
    val instruction = body.instructions(pre_pc)
    if (instruction.isStoreLocalVariableInstruction && instruction.asStoreLocalVariableInstruction.indexOfWrittenLocal == index){
      getAnotherInfos(pre_pc, obj, byteCodeInfo, body, result)
    } else {
      findInstructionStoreWithIndex(pre_pc, index, obj, body, byteCodeInfo, result)
    }
  }

  def checkOriginValue(origin: Int, obj: ReflectionUse, info: StringBuilder): Unit = {
    if (origin == -1 && !obj.method.isStatic) {
      info.append("this")
    } else
      obj.isValid = false
  }

  def objectToString(pc: Int, valueObject: StringBuilder, obj: ReflectionUse, byteCodeInfo: ByteCodeInfo, body: Code,
                     result: AIResult {val domain: DefaultDomainWithCFGAndDefUse[URL]}): Unit = {
    byteCodeInfo.pc = pc
    if (pc < 0) {
      if (pc == -1 && !obj.method.isStatic) {
        valueObject.insert(0, "this")
      } else
        obj.isValid = false
      return
    }
    val operands = result.operandsArray(pc)
    val instruction = body.instructions(pc)
    byteCodeInfo.instruction = instruction
    if (operands.equals(Naught) || operands.head.equals(operands.last) ) {
      instruction.opcode match {
        case GETFIELD.opcode =>
          val get_field = instruction.asInstanceOf[GETFIELD]
          valueObject.insert(0, "this." + get_field.name)
        case GETSTATIC.opcode =>
          val get_static = instruction.asInstanceOf[GETSTATIC]
          valueObject.insert(0, "this." + get_static.name)
        case NEW.opcode =>
          val instruction_new = instruction.asInstanceOf[NEW]
          valueObject.insert(0, "new " + instruction_new.objectType.simpleName + "()")
        case INVOKESTATIC.opcode =>
          val invokeStatic = instruction.asInstanceOf[INVOKESTATIC]
          val className = invokeStatic.declaringClass.toJava
          val methodName = invokeStatic.name
          if (className.equals(obj.className))
            valueObject.insert(0, "this." + methodName + "()")
          else {
            valueObject.insert(0, className.split('.').last + "." + methodName + "()")
          }
        case INVOKEVIRTUAL.opcode | INVOKESPECIAL.opcode | INVOKEINTERFACE.opcode =>
          val name = instruction.asInvocationInstruction.name
          valueObject.insert(0, "." + name + "()")
          result.domain.originsIterator(operands.last).foreach(org =>{
            var new_info = new ByteCodeInfo()
            byteCodeInfo.previousByteCodeInfo = Option(new_info)
            objectToString(org, valueObject, obj, new_info, body, result)
          })
        case _ =>
      }
    } else {
      val paramString = new StringBuilder()
      operands.foreach(op => {
        if (instruction.isInstanceOf[INVOKESTATIC] | !op.equals(operands.last)) {
          var new_info = new ByteCodeInfo()
          op match {
            case result.domain.StringValue(s) =>
              result.domain.originsIterator(op).foreach(org => {
                new_info.setInfo(org, body.instructions(org))
                byteCodeInfo.previousByteCodeInfo = Option(new_info)
              })
              createParamString(paramString, s, operands.head.equals(op))
            case result.domain.MultipleReferenceValues(v) =>
              obj.isValid = false
              setMultipleByteCodeInfo(v.idSet, obj, byteCodeInfo, body, result)
            case result.domain.ClassValue(v) =>
              result.domain.originsIterator(op).foreach(org => {
               new_info.setInfo(org, body.instructions(org))
                byteCodeInfo.previousByteCodeInfo = Option(new_info)
              })
              createParamString(paramString, v.asObjectType.simpleName + ".class", operands.head.equals(op))
            case result.domain.DomainReferenceValueTag(v) =>
              result.domain.originsIterator(op).foreach(org =>{
                getAnotherInfos(org, obj, new_info, body, result)
                if (org == -1 && !obj.method.isStatic) {
                  createParamString(paramString, "this", operands.head.equals(op))
                } else return
              })
            case _ =>
              obj.isValid = false
              return
          }
        } else {
          paramString.update(0, '(')
          instruction.opcode match {
            case INVOKEVIRTUAL.opcode | INVOKESPECIAL.opcode | INVOKEINTERFACE.opcode =>
              val invoke = instruction.asInvocationInstruction
              paramString.insert(0, "." + invoke.name)
              valueObject.insert(0, paramString)
              result.domain.originsIterator(op).foreach(org =>{
                var new_info = new ByteCodeInfo()
                byteCodeInfo.previousByteCodeInfo = Option(new_info)
                objectToString(org, valueObject, obj, byteCodeInfo, body, result)
              })
            case _ =>
              println("test")
          }
        }
      })
      if (instruction.isInstanceOf[INVOKESTATIC]) {
        paramString.update(0, '(')
        val invokeStatic = instruction.asInstanceOf[INVOKESTATIC]
        val className = invokeStatic.declaringClass.toJava
        val methodName = invokeStatic.name
        if (className.equals(obj.className)) {
          paramString.insert(0, "this." + methodName)
        } else {
          paramString.insert(0, className.split('.').last + "." + methodName)
        }
        valueObject.insert(0, paramString)
      }
    }
  }

  def createParamString(paramString: StringBuilder, str: String, isHead: Boolean): Unit = {
    if (isHead) {
      paramString.append("," + str + ")")
    } else {
      paramString.insert(0, "," + str)
    }
  }

  def setConstructorToObject(origin: Int, obj: ReflectionSetAccessible, byteCodeInfo: ByteCodeInfo, body: Code, result: AIResult {val domain: DefaultDomainWithCFGAndDefUse[URL]}): Unit = {
    if (origin < 0) return
    val operands = result.operandsArray(origin)
    if (operands == null) {
      return
    }
    val instruction = body.instructions(origin)
    byteCodeInfo.setInfo(origin, instruction)
    var classNames = new ListBuffer[String]()
    var new_info = new ByteCodeInfo()
    byteCodeInfo.previousByteCodeInfo = Option(new_info)
    var op = operands.head
    var classConstructor = new ClassConstructor()
    operands.foreach(op => {
      if (!obj.isValid) return
      if (!op.equals(operands.last)) {
        op match {
          case result.domain.DomainInitializedArrayValueTag(v) =>
            val array_length = v.length.get
            if (array_length > 0) {
              var param_list = new ListBuffer[ByteCodeInfo]()
              var byteCodeInfoWithArray = new ByteCodeInfoWithArray()
              getTypeParametersOfMethod(v.origin, origin, array_length, classConstructor, byteCodeInfoWithArray, body)
              param_list.append(byteCodeInfoWithArray)
              byteCodeInfo.parametersByteCodeInfo = Option(param_list)
            }
          case result.domain.MultipleReferenceValues(v) =>
            obj.isValid = false
            setMultipleByteCodeInfo(v.idSet, obj, byteCodeInfo, body, result)
          case result.domain.DomainArrayValueTag(v) =>
            v.originsIterator.foreach(org => {
              if (org < 0) {
                obj.isValid = false
                return
              }
              val instr = body.instructions(org)
              instr.opcode match {
                case GETSTATIC.opcode | GETFIELD.opcode =>
                  obj.isValid = false
                  return
                case _ =>
              }
            })
          case _ =>
        }
      }
    })

    if (!obj.isValid) return
    op = operands.last
    op match {
      case result.domain.MultipleReferenceValues(v) =>
        checkMultipleReferenceValue(v.idSet, classNames, obj, byteCodeInfo, body, result)
      case _ =>
        result.domain.originsIterator(op).foreach(org => {
          getClassInfos(org, classNames, obj, new_info, body, result)
          if (!obj.isValid) return
        })
    }
    byteCodeInfo.previousByteCodeInfo = Option(new_info)
    if (classNames.nonEmpty) {
      classConstructor.className = classNames.last
      obj.classConstructor = Option(classConstructor)
    }
  }

  def setMethodToObject(origin: Integer, obj: ReflectionUse, byteCodeInfo: ByteCodeInfo, body: Code, result: AIResult {val domain: DefaultDomainWithCFGAndDefUse[URL]}): Unit = {
    byteCodeInfo.pc = origin
    if (origin < 0) {
      obj.isValid = false
      return
    }
    val instruction = body.instructions(origin)
    byteCodeInfo.instruction = instruction
    var methodAndClass = new MethodAndClass()
    var classNames = new ListBuffer[String]()
    instruction.opcode match {
      case INVOKEVIRTUAL.opcode |
           INVOKESPECIAL.opcode |
           INVOKEINTERFACE.opcode =>
        val operands = result.operandsArray(origin)
        if (operands == null) {
          return
        }
        var lst = new ListBuffer[ByteCodeInfo]()
        operands.foreach( op =>
        {
          if (op.equals(operands.last)) {
            op match {
              case result.domain.MultipleReferenceValues(v) =>
                checkMultipleReferenceValue(v.idSet, classNames, obj, byteCodeInfo, body, result)
              case result.domain.DomainReferenceValueTag(v) =>
                var new_info = new ByteCodeInfo()
                byteCodeInfo.previousByteCodeInfo = Option(new_info)
                result.domain.originsIterator(op).foreach(org => {
                  getClassInfos(org, classNames, obj, new_info, body, result)
                })
              case _ =>
            }
          } else {
            op match {
              case result.domain.StringValue(s) =>
                var paramInfo = new ByteCodeInfo
                val org = result.domain.origins(op).head
                paramInfo.setInfo(org, body.instructions(org))
                methodAndClass.methodName = s
                lst.append(paramInfo)
              case result.domain.DomainInitializedArrayValueTag(v) =>
                val param_length = v.length.get
                if (param_length > 0) {
                  var byteCodeInfoWithArray = new ByteCodeInfoWithArray()
                  getTypeParametersOfMethod(v.origin, origin, v.length.get, methodAndClass, byteCodeInfoWithArray, body)
                  byteCodeInfoWithArray.setInfo(v.origin, body.instructions(v.origin))
                  lst.append(byteCodeInfoWithArray)
                }
              case _ =>
                obj.isValid = false
                return
            }
          }
        })
        byteCodeInfo.parametersByteCodeInfo = Option(lst)
      case _ =>
        obj.isValid = false
        return
    }
    if (obj.isValid) {
      methodAndClass.className = classNames.last
      obj.methodAndClass = Option(methodAndClass)
    }
  }

  def checkMultipleReferenceValue(origins: IntTrieSet, classNames: ListBuffer[String], obj: ReflectionUse, byteCodeInfo: ByteCodeInfo,
                                  body: Code, result: AIResult {val domain: DefaultDomainWithCFGAndDefUse[URL]}): Unit = {
    if (origins.iterator.length > 2){
      obj.isValid = false
      setMultipleByteCodeInfo(origins, obj, byteCodeInfo, body, result)
    } else {
      var new_info = new ByteCodeInfo()
      origins.foreachPair((i1, i2) => {
        if (i1 - i2 == -10 && body.instructions(i1).isInstanceOf[GETSTATIC] && body.instructions(i2).isInstanceOf[INVOKESTATIC]) {
          byteCodeInfo.previousByteCodeInfo = Option(new_info)
          getClassInfos(i2, classNames, obj, new_info, body, result)
        }
        else if  (i1 - i2 == 10 && body.instructions(i2).isInstanceOf[GETSTATIC] && body.instructions(i1).isInstanceOf[INVOKESTATIC])
        {
          byteCodeInfo.previousByteCodeInfo = Option(new_info)
          getClassInfos(i1, classNames, obj, byteCodeInfo, body, result)
        } else {
          obj.isValid = false
          setMultipleByteCodeInfo(origins, obj, byteCodeInfo, body, result)
        }
      })
    }
  }

  def getTypeParametersOfMethod(start: Integer, end: Integer, count: Integer, obj: ObjectCalledByReflection, byteCodeInfo: ByteCodeInfoWithArray, body: Code): Unit = {
    val nextPc = body.pcOfNextInstruction(start)
    if (count == 0 || nextPc == end)
      return
    var new_count = count
    val instruction = body.instructions(nextPc)
    instruction match {
      case loadClass: LoadClass =>
        if (loadClass.value.isArrayType){
          saveParametersInfoInByteCodeInfo(nextPc, loadClass.value.toJava, instruction, obj, byteCodeInfo)
        } else {
          if (loadClass.value.asObjectType.id == ObjectType.Class.id) {
            return
          }
          val paramType = loadClass.value.asObjectType.fqn
          saveParametersInfoInByteCodeInfo(nextPc, paramType, instruction, obj, byteCodeInfo)
        }
        new_count = new_count - 1
      case getStatic: GETSTATIC =>
        val paramType = getStatic.declaringClass.fqn
        saveParametersInfoInByteCodeInfo(nextPc, paramType, instruction, obj, byteCodeInfo)
        new_count = new_count - 1
      case _ =>
    }
    getTypeParametersOfMethod(nextPc, end, new_count, obj, byteCodeInfo, body)
  }

  def saveParametersInfoInByteCodeInfo(pc: Int, param: String, instruction: Instruction, obj: ObjectCalledByReflection, byteCodeInfo: ByteCodeInfoWithArray): Unit = {
    obj.addParametersPC(pc)
    obj.addParametersType(param)
    val new_info = new ByteCodeInfo()
    new_info.setInfo(pc, instruction)
    byteCodeInfo.values.append(new_info)
  }

  def setFieldToObject(origin: Integer, obj: ReflectionUse, byteCodeInfo: ByteCodeInfo, body: Code, result: AIResult {val domain: DefaultDomainWithCFGAndDefUse[URL]}): Unit = {
    byteCodeInfo.pc = origin
    if (origin < 0) {
      obj.isValid = false
      return
    }
    val instruction = body.instructions(origin)
    byteCodeInfo.instruction = instruction
    val operands = result.operandsArray(origin)
    if (operands == null || operands.equals(Naught)) {
      obj.isValid = false
      return
    }
    var new_info = new ByteCodeInfo()
    val fieldAndClass = new FieldAndClass()
    var classNames = new ListBuffer[String]()
    operands.foreach(op =>
    if (op.equals(operands.last)){
      op match {
        case result.domain.MultipleReferenceValues(v) =>
          checkMultipleReferenceValue(v.idSet, classNames, obj, byteCodeInfo, body, result)
        case result.domain.DomainReferenceValueTag(v) =>
          byteCodeInfo.previousByteCodeInfo = Option(new_info)
          result.domain.originsIterator(op).foreach(org => {
            getClassInfos(org, classNames, obj, new_info, body, result)
          })
        case _ =>
      }
    }
    else {
      op match {
        case result.domain.IntegerRange(s) =>
          if (s._1 != s._2) {
            obj.isValid = false
            return
          }
        case op@result.domain.StringValue(s) =>
          var lst = new ListBuffer[ByteCodeInfo]()
          var paramInfo = new ByteCodeInfo
          val org = result.domain.origins(op).head
          paramInfo.setInfo(org, body.instructions(org))
          fieldAndClass.fieldName = s
          lst.append(paramInfo)
          byteCodeInfo.parametersByteCodeInfo = Option(lst)
        case result.domain.MultipleReferenceValues(v) =>
          obj.isValid = false
          return
        case op@result.domain.DomainReferenceValueTag(v) =>
          result.domain.originsIterator(op).foreach(org => {
            byteCodeInfo.previousByteCodeInfo = Option(new_info)
            setFieldToObject(org, obj, new_info, body, result)
          })
        case _ =>
      }
    })
    if (classNames.nonEmpty) {
      fieldAndClass.className = classNames.last
      obj.fieldAndClass = Option(fieldAndClass)
    }
  }

  def getClassInfos(origin: Integer, classNames: ListBuffer[String], obj: ReflectionUse, byteCodeInfo: ByteCodeInfo, body: Code,
                    result: AIResult {val domain: DefaultDomainWithCFGAndDefUse[URL]}): Unit = {
    byteCodeInfo.pc = origin
    if (origin < 0) {
      if (origin == -1 && !obj.method.isStatic)
        classNames.append(obj.className)
      else
        obj.isValid = false
      return
    }
    val instruction = body.instructions(origin)
    byteCodeInfo.instruction = instruction
    val operands = result.operandsArray(origin)
    if (operands == null) return
    instruction match {
      case loadClassW: LoadClass_W =>
        val value = loadClassW.value
        classNames.append(value.asObjectType.fqn)
      case loadClass: LoadClass =>
        val value = loadClass.value
        classNames.append(value.asObjectType.fqn)
      case _: GETSTATIC =>
        obj.isValid = false
        return
      case _: GETFIELD =>
        obj.isValid = false
        return
      case invokeVirtual: INVOKEVIRTUAL =>
        if (operands.last.equals(operands.last) && (invokeVirtual.name == "get")) {
          operands.head match {
            case result.domain.MultipleReferenceValues(v) =>
              obj.isValid = false
              return
            case result.domain.DomainReferenceValueTag(v) =>
              classNames.append(v.upperTypeBound.head.asInstanceOf[ObjectType].fqn)
            case _ =>
          }
        }
      case invokeStatic: INVOKESTATIC =>
        val name = invokeStatic.name
        if (name == "getClass" && invokeStatic.methodDescriptor.parameterTypes.length > 0){
          obj.isValid = false
          return
        }
      case _ =>
    }
    instruction.opcode match {
      case INVOKEVIRTUAL.opcode |
           INVOKESPECIAL.opcode |
           INVOKESTATIC.opcode |
           INVOKEINTERFACE.opcode =>
        val methodName = instruction.asInvocationInstruction.name
        if (methodName == "getSuperclass" || methodName == "asSubclass") {
          if (!operands.head.equals(operands.last)) {
            var lst = new ListBuffer[ByteCodeInfo]()
            var param_info = new ByteCodeInfo()
            lst.append(param_info)
            byteCodeInfo.parametersByteCodeInfo = Option(lst)
            result.domain.originsIterator(operands.head).foreach(org =>
              getClassInfos(org, classNames, obj, param_info, body, result)
            )
            var new_info = new ByteCodeInfo()
            byteCodeInfo.previousByteCodeInfo = Option(new_info)
            result.domain.originsIterator(operands.last).foreach(org =>
              getAnotherInfos(org, obj, new_info, body, result)
            )
          } else {
            var new_info = new ByteCodeInfo()
            byteCodeInfo.previousByteCodeInfo = Option(new_info)
            var new_classNames = new ListBuffer[String]()
            result.domain.originsIterator(operands.last).foreach(org => {
              getClassInfos(org, new_classNames, obj, new_info, body, result)
              new_classNames.foreach(name =>
                project.allClassFiles.filter(class_file => class_file.fqn.equals(name)).foreach(class_file =>
                  if (class_file.superclassType.isDefined)
                    classNames.append(class_file.superclassType.get.fqn)
                  else
                    obj.isValid = false
                )
              )
            })
          }
        }
        else {
          var lst = new ListBuffer[ByteCodeInfo]()
          operands.foreach(op => {
            if (!obj.isValid) return
            if (methodName == "forName" || (!op.equals(operands.last) && !operands.head.equals(operands.last))) {
              var param_info = new ByteCodeInfo()
              byteCodeInfo.parametersByteCodeInfo = Option(lst)
              op match {
                case result.domain.StringValue(s) =>
                  val org = result.domain.origins(op).head
                  param_info.setInfo(org, body.instructions(org))
                  classNames.append(s)
                  lst.append(param_info)
                case result.domain.MultipleReferenceValues(v) =>
                  obj.isValid = false
                  return
                case result.domain.DomainReferenceValueTag(v) =>
                  lst.append(param_info)
                  result.domain.originsIterator(op).foreach(org => {
                    if (org <0) return
                    val instr = body.instructions(org)
                    if (instr.isInvocationInstruction && instr.asInvocationInstruction.name == "getName"){
                      getClassInfos(org, classNames, obj, param_info, body, result)
                    } else
                      getAnotherInfos(org, obj, param_info, body, result)
                  })
                case _ =>
              }
            } else {
              var lst = new ListBuffer[ByteCodeInfo]()
              var new_info = new ByteCodeInfo()
              byteCodeInfo.previousByteCodeInfo = Option(new_info)
              if (classNames.isEmpty) {
                result.domain.originsIterator(op).foreach(org => {
                  getClassInfos(org, classNames, obj, new_info, body, result)
                })
              } else {
                result.domain.originsIterator(op).foreach(org => {
                  getAnotherInfos(org, obj, new_info, body, result)
                })
              }
            }

          })
        }
      case _ =>
    }
  }

  def setMultipleByteCodeInfo(origins: IntTrieSet, obj: ReflectionUse, byteCodeInfo: ByteCodeInfo, body: Code,
                              result: AIResult {val domain: DefaultDomainWithCFGAndDefUse[URL]}): Unit ={
    origins.foreach(org => {
      var new_info= new ByteCodeInfo()
      byteCodeInfo.multiPreByteCodeInfo.append(new_info)
      getAnotherInfos(org, obj, new_info, body, result)
    })
  }

  def getAnotherInfos(org: Int, obj: ReflectionUse, byteCodeInfo: ByteCodeInfo, body: Code,
                      result: AIResult {val domain: DefaultDomainWithCFGAndDefUse[URL]}): Unit = {
    byteCodeInfo.pc = org
    if (org < 0) {
      obj.isValid = false
      return
    }
    val instruction = body.instructions(org)
    byteCodeInfo.instruction = instruction
    val operands = result.operandsArray(org)
    if (operands == null) return
    var lst = new ListBuffer[ByteCodeInfo]()
    operands.foreach(op =>
      if (!op.equals(operands.last)) {
        var param_info = new ByteCodeInfo()
        lst.append(param_info)
        op match {
          case result.domain.StringValue(s) =>
            val org = result.domain.origins(op).head
            param_info.setInfo(org, body.instructions(org))
          case result.domain.MultipleReferenceValues(v) =>
            return
          case result.domain.DomainReferenceValueTag(v) =>
            result.domain.originsIterator(op).foreach(org => {
              getAnotherInfos(org, obj, param_info, body, result)
            })
          case _ =>
        }
      } else {
        op match {
          case result.domain.MultipleReferenceValues(v) =>
            return
          case result.domain.DomainReferenceValueTag(v) =>
            var new_info = new ByteCodeInfo()
            byteCodeInfo.previousByteCodeInfo = Option(new_info)
            result.domain.originsIterator(op).foreach(org => {
              getAnotherInfos(org, obj, new_info, body, result)
            })
          case _ =>
        }
      }
    )
  }

  def getClassName(org: Int, classNames: ListBuffer[String], obj: ReflectionUse, byteCodeInfo: ByteCodeInfo, body: Code,
                   result: AIResult {val domain: DefaultDomainWithCFGAndDefUse[URL]}): Unit = {
    byteCodeInfo.pc = org
    if (org < 0) {
      obj.isValid = false
      return
    }
    val instruction = body.instructions(org)
    byteCodeInfo.instruction = instruction
    val operands = result.operandsArray(org)
    if (operands == null) return
    if (operands.equals(Naught)) {
      instruction match {
        case loadString: LoadString =>
          classNames.append(loadString.value)
        case _ =>
      }
    }
  }

  def checkInstructionCallReflection(instruction: Instruction): Boolean = {
    instruction.opcode match {
      case INVOKEVIRTUAL.opcode |
           INVOKESPECIAL.opcode |
           INVOKESTATIC.opcode |
           INVOKEINTERFACE.opcode =>
        val invoke = instruction.asMethodInvocationInstruction
        checkReflection(invoke.declaringClass.toJava)
      case _ => false
    }
  }

  def checkReflection(info: String): Boolean = {
    info.equals("java.lang.reflect.Method") || info.equals("java.lang.Class") ||
      info.equals("java.lang.reflect.Field") || info.equals("java.lang.reflect.Construction")
  }

  def isMethodUsingReflection(method: Method): Boolean = {
    if (method.body.isDefined) {
      val code = method.body.get
      val i_invoke = code.instructions.filter(instr => instr != null && instr.isInvocationInstruction)
      for (instr <- i_invoke) {
        if (checkInstructionCallReflection(instr)) {
          return true
        }
      }
      false
    }
    else false
  }

  def to_analyse(): Unit = {
    val methods_with_body = project.allMethodsWithBody
    val method_using_reflection = methods_with_body.filter(method => isMethodUsingReflection(method))
    methods_with_body.foreach(method => {
      setMethodCallReflection(method, project)
    })
    println("invoke: " + methodCallReflectionInvoke.length)
    println("invoke valid: " + countInvoke)
    println("setAccessible: " + methodCallReflectionSetAccessible.length)
    println("setAccessible valid: " + countSetAccessible)
    println("set: " + methodCallReflectionSet.length)
    println("set valid: " + countSet)
  }


}

object FirstCode{
  def main(args: Array[String]): Unit = {
    val projectJAR = "C:/Users/tam20/java_bytecode_manipulation/project/project/target/lib/test-dex2jar.jar"
    val testJAR = "C:/Users/tam20/java_bytecode_manipulation/src/test/scala/myOwnJarFile.jar"
    val temp = new AnalyseAndroidProject(projectJAR)
    temp.to_analyse()
  }
}

