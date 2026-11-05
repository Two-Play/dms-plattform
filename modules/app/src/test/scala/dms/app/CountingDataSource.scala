package dms.app

import java.lang.reflect.{InvocationHandler, InvocationTargetException, Method, Proxy}
import java.sql.Connection
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource

/** Counts the statements prepared on the connections of a data source: what a test needs to show that a
  * request takes a fixed number of queries (DMS-138).
  */
final class CountingDataSource(underlying: DataSource):
  private val counter = AtomicLong()

  def statements: Long = counter.get

  val dataSource: DataSource =
    proxy(classOf[DataSource], underlying) { (method, result) =>
      if method.getName == "getConnection" then
        proxy(classOf[Connection], result.asInstanceOf[Connection]) { (method, result) =>
          if Set("prepareStatement", "prepareCall", "createStatement").contains(method.getName) then
            val _ = counter.incrementAndGet()
          result
        }
      else result
    }

  private def proxy[A <: AnyRef](kind: Class[A], target: A)(after: (Method, AnyRef) => AnyRef): A =
    val handler: InvocationHandler = (_, method, arguments) =>
      try after(method, method.invoke(target, arguments*))
      catch case e: InvocationTargetException => throw e.getCause
    kind.cast(Proxy.newProxyInstance(kind.getClassLoader, Array(kind), handler))
