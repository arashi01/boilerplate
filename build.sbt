scalaVersion := scala3
organization := "africa.shuwari"
description := "Collection of utilities and common patterns useful across Scala 3 projects."
startYear := Some(2025)
homepage := Some(url("https://github.com/shuwariafrica/boilerplate"))
semanticdbEnabled := true
licenses := List("MIT" -> url("https://opensource.org/licenses/MIT"))
scmInfo := Some(
  ScmInfo(
    url("https://github.com/shuwariafrica/boilerplate"),
    "scm:git:https://github.com/shuwariafrica/boilerplate.git",
    Some("scm:git:git@github.com:shuwariafrica/boilerplate.git")
  )
)

Shuwari.organisationSettings

formattingSettings
nativeSettings

def scala3 = "3.8.4"
val `cats-effect` = Def.setting("org.typelevel" %% "cats-effect" % "3.7.0")
val `cats-effect-laws` = Def.setting("org.typelevel" %% "cats-effect-laws" % "3.7.0")
val `cats-effect-testkit` = Def.setting("org.typelevel" %% "cats-effect-testkit" % "3.7.0")
val `discipline-munit` = Def.setting("org.typelevel" %% "discipline-munit" % "2.0.0")
val munit = Def.setting("org.scalameta" %% "munit" % "1.3.3")
val `munit-cats-effect` = Def.setting("org.typelevel" %% "munit-cats-effect" % "2.2.0")
val `munit-scalacheck` = Def.setting("org.scalameta" %% "munit-scalacheck" % "1.3.0")
val `scala-java-time` = Def.setting("io.github.cquiroz" %% "scala-java-time" % "2.6.0")

val boilerplate =
  projectMatrix
    .in(file("modules/core"))
    .settings(compilerSettings)
    .settings(unitTestSettings)
    .settings(fileHeaderSettings)
    .settings(publishSettings)
    .jvmPlatform(Seq(scala3))
    .jsPlatform(Seq(scala3))
    .snxPlatform(Seq(scala3))

val `boilerplate-effect` =
  projectMatrix
    .in(file("modules/effect"))
    .dependsOn(boilerplate)
    .settings(compilerSettings)
    .settings(unitTestSettings)
    .settings(fileHeaderSettings)
    .settings(publishSettings)
    .settings(libraryDependencies += `cats-effect`.value)
    .settings(libraryDependencies += `cats-effect-testkit`.value % Test)
    .settings(libraryDependencies += `munit-cats-effect`.value % Test)
    .jvmPlatform(Seq(scala3))
    .jsPlatform(Seq(scala3))
    .snxPlatform(Seq(scala3))

val `boilerplate-effect-laws` =
  projectMatrix
    .in(file("modules/effect-laws"))
    .dependsOn(`boilerplate-effect`)
    .settings(compilerSettings)
    .settings(fileHeaderSettings)
    .settings(publish / skip := true)
    .settings(libraryDependencies += `cats-effect`.value)
    .settings(libraryDependencies += `cats-effect-laws`.value)
    .settings(libraryDependencies += `cats-effect-testkit`.value)
    .settings(libraryDependencies += `discipline-munit`.value % Test)
    .settings(libraryDependencies += `munit-cats-effect`.value % Test)
    .jvmPlatform(Seq(scala3))
    .jsPlatform(Seq(scala3))
    .snxPlatform(Seq(scala3))

val `boilerplate-native` =
  project
    .in(file("modules/native"))
    .enablePlugins(SNXPlugin)
    .settings(compilerSettings)
    .settings(unitTestSettings)
    .settings(fileHeaderSettings)
    .settings(publishSettings)
    .settings(SNX.classified := true)

val `boilerplate-aggregate` =
  projectMatrix
    .in(file("."))
    .settings(publish / skip := true)
    .jvmPlatform(Seq(scala3))
    .jsPlatform(Seq(scala3))
    .snxPlatform(Seq(scala3), Seq.empty, _.aggregate(`boilerplate-native`))
    .aggregate(boilerplate)
    .aggregate(`boilerplate-effect`)
    .aggregate(`boilerplate-effect-laws`)

def baseCompilerOptions = List(
  "-language:experimental.macros",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-language:strictEquality",
  "-Xkind-projector",
  "-Xmax-inlines:64",
  "-unchecked",
  "-deprecation",
  "-feature",
  "-explain",
  "-Wvalue-discard",
  "-Wnonunit-statement",
  "-Wunused:implicits",
  "-Wunused:explicits",
  "-Wunused:imports",
  "-Wunused:locals",
  "-Wunused:params",
  "-Wunused:privates",
  "-Yexplicit-nulls",
  "-Xcheck-macros",
  "-Yrequire-targetName",
  "-Ycheck-reentrant",
  "-Ycheck-mods",
  "-Werror"
)

def compilerOptions = baseCompilerOptions ++ List(
  "-Yexplicit-nulls",
  "-Xcheck-macros",
  "-Werror"
)

def compilerSettings = List(
  Compile / compile / scalacOptions ++= compilerOptions,
  Test / compile / scalacOptions ++= baseCompilerOptions,
  Compile / doc / scalacOptions := Nil,
  Test / doc / scalacOptions := Nil
) ++ scalafixSourceSettings

// Scalafix parses with scalameta, which cannot read `Slice^` and fails the whole pass on any file
// carrying it. The compiler still checks the withheld files under the full flag set.
def scalafixSourceSettings = List(
  Compile / scalafix / unmanagedSources := (Compile / unmanagedSources).value.filterNot(BoilerplateBuild.captureChecked),
  Test / scalafix / unmanagedSources := (Test / unmanagedSources).value.filterNot(BoilerplateBuild.captureChecked)
)

BoilerplateBuild.fixtureSettings

def nativeSettings = List(
  libraryDependencySchemes += "org.scala-native" % "test-interface_native0.5_3" % "always"
)

def formattingSettings = List(
  scalafmtDetailedError := true,
  scalafmtPrintDiff := true
)

def unitTestSettings: List[Setting[?]] = List(
  libraryDependencies ++= List(
    munit.value % Test,
    `munit-scalacheck`.value % Test,
    `scala-java-time`.value % Test
  ),
  testFrameworks += new TestFramework("munit.Framework")
)

def fileHeaderSettings: List[Setting[?]] =
  List(
    headerLicense := {
      val developmentTimeline =
        import java.time.Year
        val start = startYear.value.get
        val current: Int = Year.now.getValue
        if start == current then s"$current" else s"$start, $current"
      Some(HeaderLicense.MIT(developmentTimeline, "Boilerplate contributors."))
    },
    headerEmptyLine := false
  )

def publishSettings: List[Setting[?]] = List(
  packageOptions += Package.ManifestAttributes(
    "Build-Jdk" -> System.getProperty("java.version"),
    "Specification-Title" -> name.value,
    "Specification-Version" -> Keys.version.value,
    "Implementation-Title" -> name.value
  ),
  publishTo := {
    val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
    if version.value.toLowerCase.contains("snapshot") then Some("central-snapshots".at(centralSnapshots))
    else localStaging.value
  },
  pomIncludeRepository := (_ => false),
  publishMavenStyle := true
)

addCommandAlias("format", "scalafixAll; scalafmtAll; scalafmtSbt; headerCreateAll")
addCommandAlias(
  "check",
  "scalafixAll --check; scalafmtCheckAll; scalafmtSbtCheck; headerCheckAll; checkCaptureCheckedExclusions; checkCaptureEscapes"
)
