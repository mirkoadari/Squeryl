package org.squeryl.internals
 

import java.lang.Class
import java.lang.annotation.Annotation
import net.sf.cglib.proxy.{Factory, Callback, Enhancer}
import java.lang.reflect.{Member, Constructor, Method, Field}
import collection.mutable.{HashSet, ArrayBuffer}
import org.squeryl.annotations._


class PosoMetaData[T](val clasz: Class[T]) {
    

  override def toString =
    'PosoMetaData + "[" + clasz.getSimpleName + "]" + fieldsMetaData.mkString("(",",",")")

  def findFieldMetaDataForProperty(name: String) =
     fieldsMetaData.find(fmd => fmd.name == name)

  lazy val primaryKey: Option[FieldMetaData] = {

    val k = fieldsMetaData.find(fmd => fmd.name == "id")
    // TODO:implement PK detection with KeydEntity
    if(k != None) //TODO: this is config by convention, implement override for exceptions
      k.get.isAutoIncremented = true
    
    k
  }

  //TODO: implement a "0 param constructor emulator" or "sample param list builder"
  // and use this reflected param list for logging (instead of requiring a toString 
//  val constructor =
//    (for(ct <- clasz.getConstructors() if ct.getParameterTypes.size == 0)
//      yield ct).headOption.orElse(error(clasz.getName + " must have a 0 param constructor")).get

  val constructor =
    _const.headOption.orElse(error(clasz.getName +
            " must have a 0 param constructor or a constructor with only primitive types")).get

  val fieldsMetaData = buildFieldMetaData
  
  def _const = {

    val r = new ArrayBuffer[(Constructor[_],Array[Object])]

//    for(ct <- clasz.getConstructors)
//      println("CT: " + ct.getParameterTypes.map(c=>c.getName).mkString(","))
    
    for(ct <- clasz.getConstructors)
      _tryToCreateParamArray(r, ct)

    r.sortWith(
      (a:(Constructor[_],Array[Object]),
       b:(Constructor[_],Array[Object])) => a._2.length < b._2.length
    )
  }

  def _tryToCreateParamArray(
    r: ArrayBuffer[(Constructor[_],Array[Object])],
    c: Constructor[_]): Unit = {

    val params: Array[Class[_]] = c.getParameterTypes       
    val paramAnotations: Array[Array[java.lang.annotation.Annotation]] = c.getParameterAnnotations

    if(params.length == 1) {
      val cn = clasz.getName
      val test = params(0).getName + "$" + clasz.getSimpleName
      if(cn == test)
        error("inner classes are not supported, except when outter class is a singleton (object) " + cn)
    }

    var res = new Array[Object](params.size)

    for(i <- 0 to params.length -1) {
      val v = FieldMetaData.createDefaultValue(clasz, params(i), paramAnotations(i))
      res(i) = v
    }

    r.append((c, res))
  }

  private def _noOptionalColumnDeclared =
    error("class " + clasz.getName + " has an Option[] member with no Column annotation with optionType declared.")

  //def createSamplePoso[T](vxn: ViewExpressionNode[T], classOfT: Class[T]): T = {
    //Enhancer.create(classOfT, new PosoPropertyAccessInterceptor(vxn)).asInstanceOf[T]
  //}

  def createSample(cb: Callback) = _builder(cb)

  private val _builder: (Callback) => T = {


    val e = new Enhancer
    e.setSuperclass(clasz)
    val pc: Array[Class[_]] = constructor._1.getParameterTypes
    val args:Array[Object] = constructor._2
    e.setUseFactory(true)

    (callB:Callback) => {

      val cb = new Array[Callback](1)
      cb(0) = callB
      e.setCallback(callB)
      val fac = e.create(pc , constructor._2).asInstanceOf[Factory]

      fac.newInstance(pc, constructor._2, cb).asInstanceOf[T]
    }
  }

  private def _isImplicitMode = {
    
    val rowAnnotation = clasz.getAnnotation(classOf[Row])

    rowAnnotation == null ||
     rowAnnotation.fieldToColumnCorrespondanceMode == FieldToColumnCorrespondanceMode.IMPLICIT
  }

  private def buildFieldMetaData : Iterable[FieldMetaData] = {

    val isImplicitMode = _isImplicitMode

    val setters = new ArrayBuffer[Method]

//    // find setters
//    for(m <- clasz.getMethods if(m.getName.endsWith("_$eq")))
         //s <-setters if(s.getName.startsWith(g.getName)))    
//      setters.append(m);

    //TODO: inspect superclasses

    val sampleInstance4OptionTypeDeduction =
      try {
        constructor._1.newInstance(constructor._2 :_*).asInstanceOf[AnyRef];
      }
      catch {
        case e:IllegalArgumentException =>
          throw new RuntimeException("invalid constructor choice " + constructor._1, e)
        case e:Exception =>
          throw new RuntimeException("exception occured while invoking constructor : " + constructor._1, e)        
      }

    val members = new ArrayBuffer[(Member,HashSet[Annotation])]

    for(m <-clasz.getMethods if(m.getDeclaringClass != classOf[Object])) {
      m.setAccessible(true)
      val t = (m, new HashSet[Annotation])
      _addAnnotations(m, t._2)
      members.append(t)
    }

    for(m <- clasz.getDeclaredFields) {
      m.setAccessible(true)
      val t = (m, new HashSet[Annotation])
      _addAnnotations(m, t._2)      
      members.append(t)
    }

    val name2MembersMap =
      members.groupBy(m => {

        val n = m._1.getName
        val idx = n.indexOf("_$eq")
        if(idx != -1)
          n.substring(0, idx)
        else
          n
      })

    val fmds = new ArrayBuffer[FieldMetaData];

    for(e <- name2MembersMap) {
      val name = e._1
      val v = e._2

      var a:Set[Annotation] = Set.empty
      for(memberWithAnnotationTuple <- v)
        a = a.union(memberWithAnnotationTuple._2)

      val members = v.map(t => t._1)
      
      val field = members.find(m => m.isInstanceOf[Field]).map(m=> m.asInstanceOf[Field])
      val getter = members.find(m => m.isInstanceOf[Method] && m.getName == name).map(m=> m.asInstanceOf[Method])
      val setter = members.find(m => m.isInstanceOf[Method] && m.getName.endsWith("_$eq")).map(m=> m.asInstanceOf[Method])

      val property = (field, getter, setter, a)

      if(isImplicitMode && _groupOfMembersIsProperty(property)) {
        fmds.append(FieldMetaData.build(this, name, property, sampleInstance4OptionTypeDeduction))
      }
//      else {
//        val colA = a.find(an => an.isInstanceOf[Column])
//        if(colA != None)
//          fmds.append(FieldMetaData.build(this, name, property, sampleInstance4OptionTypeDeduction))
//      }
    }

    fmds
  }

  private def _groupOfMembersIsProperty(property: (Option[Field], Option[Method], Option[Method], Set[Annotation])): Boolean  = {

    if(property._4.find(an => an.isInstanceOf[Transient]) != None)
      return false    

    val hasAField = property._1 != None

    val hasGetter = property._2 != None &&
      ! classOf[java.lang.Void].isAssignableFrom(property._2.get.getReturnType) &&
      property._2.get.getParameterTypes.length == 0

    val hasSetter = property._3 != None &&
      property._3.get.getParameterTypes.length == 1
    
    val memberTypes = new ArrayBuffer[Class[_]]


    val getterIsNotVoid = ! classOf[java.lang.Void].isAssignableFrom(property._2.get.getReturnType)

    if(hasAField)
      memberTypes.append(property._1.get.getType)
    if(hasGetter)
      memberTypes.append(property._2.get.getReturnType)
    if(hasSetter)
      memberTypes.append(property._3.get.getParameterTypes.apply(0))    

    if(memberTypes.size == 0)
      return false

    val c = memberTypes.remove(0)
    for(c0 <- memberTypes) {
      if(c0 != c)
        return false
    }

    (hasAField, hasGetter, hasSetter) match {
      case (true,  false, false) => true
      case (false, true,  true)  => true
      case (true,  true,  true)  => true
      //case (true,  false, true)  => true
      case a:Any => false
    }
  }

  private def _addAnnotations(m: Field, s: HashSet[Annotation]) =
    for(a <- m.getAnnotations
      if a.isInstanceOf[Column] || a.isInstanceOf[OptionType])
        s.add(a)

  private def _addAnnotations(m: Method, s: HashSet[Annotation]) =
    for(a <- m.getAnnotations
      if a.isInstanceOf[Column] || a.isInstanceOf[OptionType])
        s.add(a)
}
