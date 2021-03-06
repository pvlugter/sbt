/* sbt -- Simple Build Tool
 * Copyright 2011 Mark Harrah
 * Copyright 2012 Johannes Rudolph
 *
 * This was basically copied from the sbt source code and then adapted to use
 * `sbtVersion in pluginCrossBuild`.
 */
package sbt

import complete.DefaultParsers._
import complete.Parser
import sbt.Keys._
import Project._
import Scope.GlobalScope
import Def.ScopedKey
import CommandStrings._
import Cross.{ spacedFirst, requireSession }
import Configurations._

/**
 * Module responsible for plugin cross building.
 */
private[sbt] object PluginCross {
  lazy val pluginSwitch: Command = {
    def switchParser(state: State): Parser[(String, String)] = {
      val knownVersions = Nil
      lazy val switchArgs = token(NotSpace.examples(knownVersions: _*)) ~ (token(Space ~> matched(state.combinedParser)) ?? "")
      lazy val nextSpaced = spacedFirst(PluginSwitchCommand)
      token(PluginSwitchCommand ~ OptSpace) flatMap { _ =>
        switchArgs & nextSpaced
      }
    }
    def crossExclude(s: Def.Setting[_]): Boolean =
      s.key match {
        case ScopedKey(Scope(_, _, pluginCrossBuild.key, _), sbtVersion.key) => true
        case _ => false
      }
    Command.arb(requireSession(switchParser), pluginSwitchHelp) {
      case (state, (version, command)) =>
        val x = Project.extract(state)
        import x._
        state.log.info(s"Setting `sbtVersion in pluginCrossBuild` to $version")
        val add = List(sbtVersion in GlobalScope in pluginCrossBuild :== version) ++
          List(scalaVersion := scalaVersionSetting.value) ++
          inScope(GlobalScope.copy(project = Select(currentRef)))(Seq(
            scalaVersion := scalaVersionSetting.value
          ))
        val cleared = session.mergeSettings.filterNot(crossExclude)
        val newStructure = Load.reapply(cleared ++ add, structure)
        Project.setProject(session, newStructure, command :: state)
    }
  }

  lazy val pluginCross: Command = {
    def crossParser(state: State): Parser[String] =
      token(PluginCrossCommand <~ OptSpace) flatMap { _ =>
        token(matched(state.combinedParser &
          spacedFirst(PluginCrossCommand)))
      }
    def crossVersions(state: State): Seq[String] = {
      val x = Project.extract(state)
      import x._
      crossSbtVersions in currentRef get structure.data getOrElse Nil
    }
    Command.arb(requireSession(crossParser), pluginCrossHelp) {
      case (state, command) =>
        val x = Project.extract(state)
        import x._
        val versions = crossVersions(state)
        val current = (sbtVersion in pluginCrossBuild)
          .get(structure.data)
          .map(PluginSwitchCommand + " " + _).toList
        if (versions.isEmpty) command :: state
        else versions.map(PluginSwitchCommand + " " + _ + " " + command) ::: current ::: state
    }
  }

  def scalaVersionSetting: Def.Initialize[String] = Def.setting {
    val scalaV = scalaVersion.value
    val sv = (sbtBinaryVersion in pluginCrossBuild).value
    if (sbtPlugin.value) scalaVersionFromSbtBinaryVersion(sv)
    else scalaV
  }

  def scalaVersionFromSbtBinaryVersion(sv: String): String =
    VersionNumber(sv) match {
      case VersionNumber(Seq(0, 12, _*), _, _) => "2.9.2"
      case VersionNumber(Seq(0, 13, _*), _, _) => "2.10.7"
      case VersionNumber(Seq(1, 0, _*), _, _)  => "2.12.4"
      case _                                   => sys.error(s"Unsupported sbt binary version: $sv")
    }
}
