package org.squeryl.dsl

import ast.{QueryableExpressionNode, ViewExpressionNode, ExpressionNode, QueryExpressionNode}
import java.sql.ResultSet
import org.squeryl.internals._
import org.squeryl.{View, Queryable, Session, Query}
import collection.mutable.ArrayBuffer

abstract class AbstractQuery[R](val isRoot:Boolean) extends Query[R] {

  var selectDistinct = false
  
  var forUpdate = false

  val resultSetMapper = new ResultSetMapper

  val name = "query"

  def give(rsm: ResultSetMapper, rs: ResultSet): R = {
    rsm.pushYieldedValues(rs)
    val r = invokeYield(rsm, rs)
    r
  }

  protected def buildAst(qy: QueryYield[R], subQueryables: SubQueryable[_]*) = {


    val subQueries = new ArrayBuffer[QueryableExpressionNode]

    val views = new ArrayBuffer[ViewExpressionNode[_]]

    for(sq <- subQueryables)
      if(! sq.isQuery)
        views.append(sq.node.asInstanceOf[ViewExpressionNode[_]])

    for(sq <- subQueryables)
      if(sq.isQuery)
        subQueries.append(sq.node.asInstanceOf[QueryExpressionNode[_]])
    
    val qen = new QueryExpressionNode[R](this, qy, subQueries, views)
    val (sl,d) = qy.invokeYieldForAst(qen, resultSetMapper)
    qen.setOutExpressionNodesAndSample(sl, d)
    qen
  }

  def ast: QueryExpressionNode[R]

  def copy(asRoot:Boolean) = {
    val c = createCopy(asRoot)
    c.selectDistinct = selectDistinct
    c
  }

  def createCopy(asRoot:Boolean): AbstractQuery[R]

  def dumpAst = ast.dumpAst

  def statement: String = _genStatement(true)

  private def _genStatement(forDisplay: Boolean) = {

    val sw = new StatementWriter(forDisplay, Session.currentSession.databaseAdapter)
    ast.write(sw)
    sw.statement
  }

  def Distinct = {
    val c = copy(true)
    c.selectDistinct = true;
    c
  }

  def ForUpdate = {
    val c = copy(true)
    c.forUpdate = true;
    c    
  }

  private def _dbAdapter = Session.currentSession.databaseAdapter

  override def iterator = new Iterator[R] {

    val sw = new StatementWriter(false, _dbAdapter)
    ast.write(sw)
    val s = Session.currentSession
    val rs = _dbAdapter.executeQuery(s, sw)

    var _nextCalled = false;
    var _hasNext = false;

    def _next = {
      _hasNext = rs.next
      _nextCalled = true
    }

    def hasNext = {
      if(!_nextCalled)
        _next
      _hasNext
    }

    def next: R = {
      if(!_nextCalled)
        _next
      if(!_hasNext)
        error("next called with no rows available")
      _nextCalled = false

      if(s.isLoggingEnabled)
        s.log(rs.toString)

      give(resultSetMapper, rs)
    }
  }

  override def toString = dumpAst + "\n" + _genStatement(true)


  def createSubQueryable[U](q: Queryable[U]) =
    if(q.isInstanceOf[View[_]]) {
      val v = q.asInstanceOf[View[U]]
      val vxn = new ViewExpressionNode(v)
      vxn.sample =
        v.posoMetaData.createSample(FieldReferenceLinker.createCallBack(vxn))
      
      new SubQueryable(v, vxn.sample, vxn.resultSetMapper, false, vxn)
    }
    else {
      val qr = q.asInstanceOf[AbstractQuery[U]]
      val copy = qr.copy(false)
      new SubQueryable(copy, copy.ast.sample.asInstanceOf[U], copy.resultSetMapper, true, copy.ast)
    }

  class SubQueryable[U]
    (val queryable: Queryable[U],
     val sample: U,
     val resultSetMapper: ResultSetMapper,
     val isQuery:Boolean,
     val node: QueryableExpressionNode) {

    def give(rs: ResultSet): U = 
      if(node.isOptionalInOuterJoin && resultSetMapper.isNoneInOuterJoin(rs))
        sample
      else
        queryable.give(resultSetMapper, rs)
  }
}
