package aug.script

import java.io.File
import java.net.{URL, URLClassLoader}

import aug.profile._
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

import scala.util.{Failure, Success, Try}

object JailClassLoader {
  val log = Logger(LoggerFactory.getLogger(JailClassLoader.getClass))
  val scriptPackage = "aug.script"
}

/**
  * <p>
  * This class loader jails some packages for scripts in order to prevent cross contamination between running profiles
  * and in order to allow for reloading scripts on the fly.
  * </p>
  *
  * <p>
  * jailPackages are base package names which will be loaded in the jail, while all other classes falling outside those
  * packages are pulled from the common class loader. "amc.script" by default is a jailed package. However, this class
  * loader class, despite being in this package, is loaded, of course, by the common class loader.
  * </p>
  *
  * @author austin
  *
  */
class JailClassLoader(val urls: Array[URL], jailPackages: Set[String]) extends
  ClassLoader(Thread.currentThread().getContextClassLoader) {

  import JailClassLoader._

  private class DetectClass(val parent: ClassLoader) extends ClassLoader(parent) {
    override def findClass(name: String) = super.findClass(name)
  }

  private class ChildClassLoader(val urls: Array[URL], realParent: DetectClass, jailPackages: Set[String])
    extends URLClassLoader(urls,null) {
    override def findClass(name: String): Class[_] = {

      if(!isJailed(name)) {
        log.trace("FREE: {}",name)
        realParent.loadClass(name)
      } else {

        Try {
          log.trace("JAILED {}", name)

          Option(super.findLoadedClass(name)) getOrElse {
            super.findClass(name)
          }
        } match {
          case Failure(e) =>
            log.error(s"failed to load in jail $name", e)
            realParent.loadClass(name)
          case Success(c) => c
        }
      }
    }
    def isJailed(name: String) = jailPackages.exists { p => p.length > 0 && name.startsWith(p) }
  }

  private val childClassLoader = new ChildClassLoader(urls,new DetectClass(getParent),jailPackages + scriptPackage)

  override protected def loadClass(name: String, resolve: Boolean) : Class[_] = {
    Try {
      childClassLoader.findClass(name)
    } match {
      case Failure(e) => super.loadClass(name,resolve)
      case Success(c) => c
    }
  }


}

sealed trait ScriptEvent


object ScriptManager {
  val log = Logger(LoggerFactory.getLogger(ScriptManager.getClass))
}

class ScriptManager(scriptDir: File, scriptClass: String, profile: Profile) extends ProfileEventListener {
  import ScriptManager._

  private val jailLoader = new JailClassLoader(classpath,jailed)

  private val scriptRunnerType = jailLoader.loadClass(classOf[ScriptRunner].getCanonicalName)
  private val scriptRunner = scriptRunnerType.newInstance

  invoke("init",scriptDir,scriptClass,profile)

  private def jailed = profile.getString(PPScriptJail).split(",").toSet

  private def classpath: Array[URL] = {
    val classpath: String = System.getProperty("java.class.path")
    val urls = Seq.newBuilder[URL]
    for (dir <- classpath.split(":")) yield {
      log.trace("Adding classpath URL {}", dir)
      urls += new File(dir).toURI.toURL
    }

    if (scriptDir.exists && scriptDir.isDirectory) {
      urls += scriptDir.toURI.toURL
    }

    urls.result.toArray
  }

  override def event(event: ProfileEvent, data: Option[String]): Unit = {
    Try {
      invoke("event", event, data)
    } match {
      case Failure(e) => log.error("error calling into event",e)
      case _ =>
    }
  }

  def invoke(name:String, args: Any*): Unit = {
    val classes = for(arg<-args) yield {
      arg.getClass
    }

    scriptRunnerType.getMethod(name,classes: _*).invoke(scriptRunner,args)
  }
}

class ScriptRunner extends ProfileEventListener {


  override def event(event: ProfileEvent, data: Option[String]): Unit = {

  }
}

object ScriptLoader {
  val log = Logger(LoggerFactory.getLogger(ScriptLoader.getClass))
  val sharedClasses = Set(
    classOf[ProfileEventListener],
    classOf[ProfileEvent],
    TelnetConnect.getClass

  ) map { _.getCanonicalName }

  def classpath: Array[URL] = {
    val classpath: String = System.getProperty("java.class.path")
    val urls = Seq.newBuilder[URL]
    for (dir <- classpath.split(":")) yield {
      log.trace("Adding classpath URL {}", dir)
      urls += new File(dir.replaceAll("\\;C","")).toURI.toURL
    }

    urls.result.toArray
  }

  def main(args: Array[String]) : Unit = {
    val sl = new ScriptLoader(classpath)
    val ct = sl.loadClass(classOf[PrintEvent].getCanonicalName)

    val profile = new ProfileInterface {
      override def info(s: String, window: String): Unit = {println(s"INFO: $s")}
    }

    val pe = ct.getConstructor(classOf[ProfileInterface]).newInstance(profile).asInstanceOf[ProfileEventListener]

    pe.event(TelnetConnect,None)
    pe.event(TelnetRecv,Some("hello world"))

    println(s"${classOf[ProfileEventListener]== ct} equality")

    val ct2 = Class.forName(classOf[ProfileEventListener].getCanonicalName)

    println(s"${classOf[ProfileEventListener]== ct2} equality")
  }
}

class ScriptLoader(val urls: Array[URL]) extends ClassLoader(Thread.currentThread().getContextClassLoader) {

  import ScriptLoader._

  private class DetectClass(val parent: ClassLoader) extends ClassLoader(parent) {
    override def findClass(name: String) = super.findClass(name)
  }

  private class ChildClassLoader(val urls: Array[URL], realParent: DetectClass) extends URLClassLoader(urls,null) {

    override def findClass(name: String): Class[_] = {

      if(deferToParent(name)) {
        log.trace("FREE: {}",name)
        realParent.loadClass(name)
      } else {

        Try {
          log.trace("JAILED {}", name)

          Option(super.findLoadedClass(name)) getOrElse {
            super.findClass(name)
          }
        } match {
          case Failure(e) =>
            log.error(s"failed to load in jail $name", e)
            realParent.loadClass(name)
          case Success(c) => c
        }
      }
    }

    def deferToParent(name:String) : Boolean = {
      name.startsWith("aug.profile.") ||
      name.startsWith("java") ||
      name.startsWith("scala")
    }
  }

  private val childClassLoader = new ChildClassLoader(urls,new DetectClass(getParent))

  override protected def loadClass(name: String, resolve: Boolean) : Class[_] = {
    Try {
      childClassLoader.findClass(name)
    } match {
      case Failure(e) => super.loadClass(name,resolve)
      case Success(c) => c
    }
  }
}

class PrintEvent(val profile: ProfileInterface) extends ProfileEventListener {
  override def event(event: ProfileEvent, data: Option[String]): Unit = {
    event match {
      case TelnetConnect =>
        println("tc bitches")
        profile.info("--connected--")
      case TelnetRecv => println(s"recv $data")
      case e => println(s"unhandled e")
    }
  }
}