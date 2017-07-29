package aug.profile

import java.io.File
import java.lang.Boolean
import java.util

import aug.script.shared.{ProfileInterface, TextWindowInterface, WindowReference}

/**
  * <p>Use this class to collect the actions a script can call.  This class should
  * only ever be called by the script.</p>
  * @param profile the profile to proxy commands to
  */
class ProfileProxy(profile: Profile) extends ProfileInterface {
  import profile.offer

  override def send(cmds: String): Unit = offer(SendData(cmds))
  override def sendSilently(cmds: String): Unit = offer(SendData(cmds, true))
  override def setWindowGraph(windowReference: WindowReference): Boolean = profile.setWindowGraph(windowReference)
  override def getWindowNames: util.List[String] = profile.getWindowNames
  override def createTextWindow(name: String): TextWindowInterface = profile.createTextWindow(name)
  override def getTextWindow(name: String): TextWindowInterface = profile.getTextWindow(name)
  override def getConfigDir: File = ConfigManager.getClientDir(profile.name)
  override def logText(log: Boolean): Unit = offer(ProfileLog(log, false))
  override def logColor(log: Boolean): Unit = offer(ProfileLog(log, true))
}
