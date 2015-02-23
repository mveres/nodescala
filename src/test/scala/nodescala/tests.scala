package nodescala


import com.sun.org.apache.xalan.internal.xsltc.cmdline.getopt.IllegalArgumentException

import scala.language.postfixOps
import scala.util.{Try, Success, Failure}
import scala.collection._
import scala.concurrent._
import ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.async.Async.{async, await}
import org.scalatest._
import NodeScala._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class NodeScalaSuite extends FunSuite {

  test("A Future should always be created") {
    val always = Future.always(517)

    assert(Await.result(always, 0 nanos) == 517)
  }

  test("A Future should never be created") {
    val never = Future.never[Int]

    try {
      Await.result(never, 1 second)
      assert(false)
    } catch {
      case t: TimeoutException => // ok!
    }
  }

  test("Any returns the first future that completes"){
    val f1 =Future {
      Thread.sleep(300)
      42}

    val f2 =Future {
      Thread.sleep(400)
      43}

    val first = Future.any(List(f1, f2))
    assert(Await.result(first, 1 second) === 42)
  }

  test("All returns the a future with all values"){
    val all = Future.all(List(Future{11}, Future{42}))
    assert(Await.result(all, 1 second) === List(11, 42))
  }

  test("Delay works") {
    val delay = Future.delay(500 milliseconds)

    try {
      Await.result(delay, 550 milliseconds)
      assert(true)
    } catch {
      case t: TimeoutException => assert (false)
    }
  }

  test ("Now returns the result of the completed future"){
    assert(Future.always(1).now === 1)
  }

  test ("Now fails if the future is not completed"){
    try {
      Future.never.now
    }
    catch {
      case e: NoSuchElementException => // alright
    }
  }

  test ("Now fails if the future fails"){
    val f = Future { throw new scala.IllegalArgumentException()}
    blocking { Thread.sleep(10) }

    try {
      f.now
    }
    catch {
      case e: scala.IllegalArgumentException => // alright
    }
  }

  test ("continueWith works") {
    val f1 = Future {
      blocking { Thread.sleep(50) }
      42
    }

    val f2 = f1.continueWith(f => 43)

    assert(Await.result(f2, 70 millis) === 43)
  }

  test ("continue works"){
    val f1 = Future {
      blocking { Thread.sleep(50) }
      42
    }

    val f2 = f1.continue(t => 43)

    assert(Await.result(f2, 70 millis) === 43)
  }

  test ("CancellationTokenSource should allow stopping the computation") {
    val cts = CancellationTokenSource()
    val ct = cts.cancellationToken
    val p = Promise[String]()

    async {
      while (ct.nonCancelled) {
        // do work
      }

      p.success("done")
    }

    cts.unsubscribe()
    assert(Await.result(p.future, 1 second) == "done")
  }

  test ("run works"){
    val p = Promise[String]()
    val working = Future.run() { ct =>
      Future {
        while (ct.nonCancelled) {
          println("working")
        }
        p.success("done")
      }
    }
    Future.delay(20 millis) onSuccess {
      case _ => {
        working.unsubscribe()
        assert(Await.result(p.future, 20 millis) === "done")
      }
    }
  }

  class DummyExchange(val request: Request) extends Exchange {
    @volatile var response = ""
    val loaded = Promise[String]()
    def write(s: String) {
      response += s
    }
    def close() {
      loaded.success(response)
    }
  }

  class DummyListener(val port: Int, val relativePath: String) extends NodeScala.Listener {
    self =>

    @volatile private var started = false
    var handler: Exchange => Unit = null

    def createContext(h: Exchange => Unit) = this.synchronized {
      assert(started, "is server started?")
      handler = h
    }

    def removeContext() = this.synchronized {
      assert(started, "is server started?")
      handler = null
    }

    def start() = self.synchronized {
      started = true
      new Subscription {
        def unsubscribe() = self.synchronized {
          started = false
        }
      }
    }

    def emit(req: Request) = {
      val exchange = new DummyExchange(req)
      if (handler != null) handler(exchange)
      exchange
    }
  }

  class DummyServer(val port: Int) extends NodeScala {
    self =>
    val listeners = mutable.Map[String, DummyListener]()

    def createListener(relativePath: String) = {
      val l = new DummyListener(port, relativePath)
      listeners(relativePath) = l
      l
    }

    def emit(relativePath: String, req: Request) = this.synchronized {
      val l = listeners(relativePath)
      l.emit(req)
    }
  }

  test("Listener should serve the next request as a future") {
    val dummy = new DummyListener(8191, "/test")
    val subscription = dummy.start()

    def test(req: Request) {
      val f = dummy.nextRequest()
      dummy.emit(req)
      val (reqReturned, xchg) = Await.result(f, 1 second)

      assert(reqReturned == req)
    }

    test(immutable.Map("StrangeHeader" -> List("StrangeValue1")))
    test(immutable.Map("StrangeHeader" -> List("StrangeValue2")))

    subscription.unsubscribe()
  }

  test("Server should serve requests") {
    val dummy = new DummyServer(8191)
    val dummySubscription = dummy.start("/testDir") {
      request => for (kv <- request.iterator) yield (kv + "\n")
    }

    // wait until server is really installed
    Thread.sleep(500)

    def test(req: Request) {
      val webpage = dummy.emit("/testDir", req)
      val content = Await.result(webpage.loaded.future, 1 second)
      val expected = (for (kv <- req.iterator) yield (kv + "\n")).mkString
      assert(content == expected, s"'$content' vs. '$expected'")
    }

    test(immutable.Map("StrangeRequest" -> List("Does it work?")))
    test(immutable.Map("StrangeRequest" -> List("It works!")))
    test(immutable.Map("WorksForThree" -> List("Always works. Trust me.")))

    dummySubscription.unsubscribe()
  }

}




