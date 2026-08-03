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

// Shuwari org POM defaults: organizationName, organizationHomepage, developers, versionScheme.
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
  Compile / scalafix / unmanagedSources := (Compile / unmanagedSources).value.filterNot(captureChecked),
  Test / scalafix / unmanagedSources := (Test / unmanagedSources).value.filterNot(captureChecked)
)

def captureChecked(source: File): Boolean =
  source.getName.endsWith(".scala") &&
    IO.readLines(source).exists(line => line.trim.matches("""import (scala\.)?language\.experimental\.captureChecking"""))

// scalafix withholds by content and scalafmt by path, so a newly capture-checked file can be
// withheld from one and not the other, breaking `format`.
val checkCaptureCheckedExclusions =
  taskKey[Unit]("Verify .scalafmt.conf's exclusion list matches the capture-checked sources on disk.")

// Uncached: the inputs are discovered rather than declared, so a cached success would outlive the
// divergence this exists to catch.
LocalRootProject / checkCaptureCheckedExclusions := Def.uncached {
  val root = (LocalRootProject / baseDirectory).value
  val declared = IO
    .readLines(root / ".scalafmt.conf")
    .dropWhile(line => !line.startsWith("project.excludeFilters"))
    .takeWhile(line => !line.startsWith("]"))
    .flatMap(line => """"([^"]+)"""".r.findFirstMatchIn(line).map(_.group(1)))
    .toSet
  val present = ((root / "modules") ** "*.scala")
    .get()
    .filter(captureChecked)
    .map(file => root.toPath.relativize(file.toPath).toString.replace('\\', '/'))
    .toSet
  if declared != present then
    sys.error(
      s"""|.scalafmt.conf's capture-checking exclusions are out of step with the sources:
          |  declared but not capture-checked: ${(declared -- present).toList.sorted.mkString(", ")}
          |  capture-checked but not declared: ${(present -- declared).toList.sorted.mkString(", ")}""".stripMargin
    )
}

// Capture-checking negatives cannot be suite rows: `typeCheckErrors` compiles its snippet in a
// nested scope, where the language import is rejected and the body is never capture-checked.
val checkCaptureEscapes =
  taskKey[Unit]("Compile each capture-checking escape in isolation and assert its exact diagnostic.")

// (label, source body, expected diagnostic fragment - empty means it must compile cleanly)
def nativeEscapes = List(
  ("borrowed view returned", "def f(p: Ptr[Byte]): Slice = Slice.borrowing(p, 4)(v => v)", "outlives its scope"),
  ("borrowed view in a container", "def f(p: Ptr[Byte]): List[Slice] = Slice.borrowing(p, 4)(v => List(v))", "outlives its scope"),
  ("re-sliced view returned", "def f(p: Ptr[Byte]): Slice = Slice.borrowing(p, 4)(v => v.take(2))", "outlives its scope"),
  ("chained re-slice returned", "def f(p: Ptr[Byte]): Slice = Slice.borrowing(p, 4)(v => v.drop(1).take(1))", "outlives its scope"),
  (
    "derived view inside an Either",
    "def f(p: Ptr[Byte]): Either[SliceError, Slice] = Slice.borrowing(p, 4)(v => v.sliceOrError(0, 2))",
    "outlives its scope"
  ),
  ("secret view re-sliced out of use", "def f: Slice = Secret.fill(4)(_ => ()).use(v => v.take(2))", "outlives its scope"),
  // `init` returns Unit, so the only way out is the closure - the shape a reader most easily takes
  // for safe, and the one an `inline` on `fill` would silently open.
  (
    "fill view stashed by the closure",
    "def f(): Option[Slice] = { var stash: Option[Slice] = None; val _ = Secret.fill(4)(v => stash = Some(v)); stash }",
    "cannot flow into capture set"
  )
)

def effectEscapes = List(
  ("wiping view through an inline constructor", "val x: Eff[Nothing, Slice] = acquire.wiping(v => Eff.succeed(v))", "is boxed but"),
  ("wiping view through IO", "val x: Eff[Nothing, Slice] = acquire.wiping(v => IO.pure(v))", "outlives its scope"),
  ("useEff view through an inline constructor", "val x: Eff[Nothing, Slice] = secret.useEff(v => Eff.succeed(v))", "is boxed but"),
  ("useEff view through IO", "val x: Eff[Nothing, Slice] = secret.useEff(v => IO.pure(v))", "outlives its scope"),
  // Protected today by delegating to the non-inline `Secret.fill`; the row gates the contract, so
  // reimplementing `scoped` to allocate its own buffer cannot silently drop the protection.
  (
    "scoped view stashed by the closure",
    "def f(): Option[Slice] = { var stash: Option[Slice] = None; val _ = Secret.scoped(4)(v => stash = Some(v)); stash }",
    "cannot flow into capture set"
  ),
  ("POSITIVE: a copy outlives the borrow",
   "val x: Eff[Nothing, List[Byte]] = acquire.wiping(v => Eff.succeed(v.drop(1).toArray.toList))",
   ""
  )
)

def nativeEscapeSource(body: String) =
  s"""package boilerplate
     |import scala.language.experimental.captureChecking
     |import scala.scalanative.unsafe.*
     |object CaptureEscape:
     |  $body
     |""".stripMargin

def effectEscapeSource(body: String) =
  s"""package boilerplate.effect
     |import scala.language.experimental.captureChecking
     |import cats.effect.IO
     |import boilerplate.Secret
     |import boilerplate.Slice
     |object CaptureEscape:
     |  val acquire: IO[Slice] = IO(Slice.of(Array[Byte](1, 2, 3)))
     |  val secret = Secret.fill(4)(_ => ())
     |  $body
     |""".stripMargin

// Diagnostics come back through the compiler's own SimpleReporter, not by capturing stdout: the
// compiler loader has its own `scala.Console`, so redirection from here captures nothing.
def compileEscape(loader: ClassLoader, classpath: String, dest: File, source: File): String =
  val module = loader.loadClass("dotty.tools.dotc.Main$").getField("MODULE$").get(null)
  val reporterClass = loader.loadClass("dotty.tools.dotc.interfaces.SimpleReporter")
  val callbackClass = loader.loadClass("dotty.tools.dotc.interfaces.CompilerCallback")
  val diagnosticClass = loader.loadClass("dotty.tools.dotc.interfaces.Diagnostic")
  val messageMethod = diagnosticClass.getMethod("message")
  val collected = new java.util.ArrayList[String]()
  val handler = new java.lang.reflect.InvocationHandler:
    def invoke(proxy: Object, method: java.lang.reflect.Method, args: Array[Object]): Object =
      if method.getName == "report" then collected.add(String.valueOf(messageMethod.invoke(args(0))))
      null
  val reporter = java.lang.reflect.Proxy.newProxyInstance(loader, Array(reporterClass), handler)
  val process = module.getClass.getMethod("process", classOf[Array[String]], reporterClass, callbackClass)
  val args = Array("-classpath", classpath, "-d", dest.getAbsolutePath, source.getAbsolutePath)
  val _ = process.invoke(module, args, reporter, null)
  collected.toArray.mkString("\n")
end compileEscape

def assertEscape(label: String, expected: String, output: String): Option[String] =
  if expected.isEmpty then if output.trim.isEmpty then None else Some(s"  - $label: expected a clean compile, got:\n${output.take(600)}")
  else if output.trim.isEmpty then Some(s"  - $label: expected the diagnostic to contain '$expected', but the compiler reported nothing")
  else if output.contains(expected) then None
  else Some(s"  - $label: expected the diagnostic to contain '$expected', got:\n${output.take(600)}")

LocalRootProject / checkCaptureEscapes := Def.uncached {
  given FileConverter = fileConverter.value
  def classpathOf(cp: Seq[java.nio.file.Path]) =
    cp.map(_.toAbsolutePath.toString).mkString(java.io.File.pathSeparator)
  val nativeCp = classpathOf((LocalProject("boilerplateNative") / Test / fullClasspath).value.files)
  val effectCp = classpathOf((LocalProject("boilerplate-effect") / Test / fullClasspath).value.files)
  val loader = (LocalProject("boilerplate-effect") / scalaInstance).value.loaderCompilerOnly
  val work = (LocalRootProject / target).value / "capture-escapes"
  IO.delete(work)
  IO.createDirectory(work)

  def run(prefix: String, path: String, render: String => String, shapes: List[(String, String, String)]) =
    shapes.zipWithIndex.flatMap { case ((label, body, expected), index) =>
      val source = work / s"$prefix$index.scala"
      val dest = work / s"$prefix$index-out"
      IO.write(source, render(body))
      IO.createDirectory(dest)
      assertEscape(label, expected, compileEscape(loader, path, dest, source))
    }

  val failures = run("native", nativeCp, nativeEscapeSource, nativeEscapes) ++
    run("effect", effectCp, effectEscapeSource, effectEscapes)
  if failures.nonEmpty then sys.error(s"Capture-checking escape fixture failed:\n${failures.mkString("\n")}")
}

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
