package scala.tools.nsc.interactive

import collection.mutable.ArrayBuffer
import nsc.util.Position

trait ContextTrees { self: Global =>

  type Context = analyzer.Context
  type Contexts = ArrayBuffer[ContextTree]

  /** A context tree contains contexts that are indexed by positions.
   *  It satisfies the following properties:
   *  1. All context come from compiling the same unit.
   *  2. Child contexts have parent contexts in their outer chain.
   *  3. The `pos` field of a context is the same as `context.tree.pos`, unless that
   *     position is transparent. In that case, `pos` equals the position of
   *     one of the solid descendants of `context.tree`.
   *  4. Children of a context have non-overlapping increasining positions.
   *  5. No context in the tree has a transparent position.
   */
  class ContextTree(val pos: Position, val context: Context, val children: ArrayBuffer[ContextTree]) {
    def this(pos: Position, context: Context) = this(pos, context, new ArrayBuffer[ContextTree])
    override def toString = "ContextTree("+pos+", "+children+")"
  }

  /** Optionally return the smallest context that contains given `pos`, or None if none exists.
   */
  def locateContext(contexts: Contexts, pos: Position): Option[Context] = {
    if (contexts.isEmpty) None
    else {
      val hi = contexts.length - 1
      if ((contexts(hi).pos precedes pos) || (pos precedes contexts(0).pos)) None
      else {
        def loop(lo: Int, hi: Int): Option[Context] = {
          val mid = (lo + hi) / 2
          val midpos = contexts(mid).pos
          if ((pos precedes midpos) && (mid < hi))
            loop(lo, mid)
          else if ((midpos precedes pos) && (lo < mid))
            loop(mid, hi)
          else if (midpos includes pos)
            Some(contexts(mid).context)
          else if (contexts(mid+1).pos includes pos)
            Some(contexts(mid+1).context)
          else None
        }
        loop(0, hi)
      }
    }
  }

  /** Insert a context at correct position into a buffer of context trees.
   *  If the `context` has a transparent position, add it multiple times
   *  at the positions of all its solid descendant trees.
   */
  def addContext(contexts: Contexts, context: Context) {
    val cpos = context.tree.pos
    if (isTransparent(cpos))
      for (t <- context.tree.children flatMap solidDescendants)
        addContext(contexts, context, t.pos)
    else
      addContext(contexts, context, cpos)
  }

  /** Insert a context with non-transparent position `cpos`
   *  at correct position into a buffer of context trees.
   */
  def addContext(contexts: Contexts, context: Context, cpos: Position) {
    try {
      if (!cpos.isDefined || cpos.isSynthetic) {}
      else if (contexts.isEmpty) contexts += new ContextTree(cpos, context)
      else {
        val hi = contexts.length - 1
        if (contexts(hi).pos properlyPrecedes cpos)
          contexts += new ContextTree(cpos, context)
        else if (contexts(hi).pos properlyIncludes cpos) // fast path w/o search
          addContext(contexts(hi).children, context, cpos)
        else if (cpos properlyPrecedes contexts(0).pos)
          new ContextTree(cpos, context) +: contexts
        else {
          def insertAt(idx: Int): Boolean = {
            val oldpos = contexts(idx).pos
            if (oldpos sameRange cpos) {
              contexts(idx) = new ContextTree(cpos, context, contexts(idx).children)
              true
            } else if (oldpos includes cpos) {
              addContext(contexts(idx).children, context, cpos)
              true
            } else if (cpos includes oldpos) {
              val start = contexts.indexWhere(cpos includes _.pos)
              val last = contexts.lastIndexWhere(cpos includes _.pos)
              contexts(start) = new ContextTree(cpos, context, contexts.slice(start, last + 1))
              contexts.remove(start + 1, last - start)
              true
            } else false
          }
          def loop(lo: Int, hi: Int) {
            if (hi - lo > 1) {
              val mid = (lo + hi) / 2
              val midpos = contexts(mid).pos
              if (cpos precedes midpos)
                loop(lo, mid)
              else if (midpos precedes cpos)
                loop(mid, hi)
            } else if (!insertAt(lo) && !insertAt(hi)) {
              val lopos = contexts(lo).pos
              val hipos = contexts(hi).pos
              if ((lopos precedes cpos) && (cpos precedes hipos))
                contexts.insert(hi, new ContextTree(cpos, context))
              else
                inform("internal error? skewed positions: "+lopos+" !< "+cpos+" !< "+hipos)
            }
          }
          loop(0, hi)
        }
      }
    } catch {
      case ex: Throwable =>
        println(ex)
        ex.printStackTrace()
        println("failure inserting "+cpos+" into "+contexts+"/"+contexts(contexts.length - 1).pos+"/"+
                (contexts(contexts.length - 1).pos includes cpos))
        throw ex
    }
  }
}

