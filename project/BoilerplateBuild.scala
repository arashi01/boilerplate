import sbt.*
import sbt.Keys.*

import xsbti.FileConverter

/** Build-internal verification machinery for the capture-checked sources: the escape fixture and
  * the formatter-exclusion divergence check, both wired into the `check` alias by `build.sbt`.
  */
object BoilerplateBuild extends AutoPlugin:

  // Capture-checking negatives cannot be suite rows: `typeCheckErrors` compiles its snippet in a
  // nested scope, where the language import is rejected and the body is never capture-checked.
  val checkCaptureEscapes =
    taskKey[Unit]("Compile each capture-checking escape in isolation and assert its exact diagnostic.")

  // scalafix withholds by content and scalafmt by path, so a newly capture-checked file can be
  // withheld from one and not the other, breaking `format`.
  val checkCaptureCheckedExclusions =
    taskKey[Unit]("Verify .scalafmt.conf's exclusion list matches the capture-checked sources on disk.")

  /** True for a source carrying the capture-checking language import - the predicate the
    * per-project scalafix withholding and the exclusion divergence check share.
    */
  def captureChecked(source: File): Boolean =
    source.getName.endsWith(".scala") &&
      IO.readLines(source).exists(line => line.trim.matches("""import (scala\.)?language\.experimental\.captureChecking"""))

  // Uncached deliberately: both tasks discover their inputs rather than declare them, so a cached
  // success would outlive the divergence they exist to catch.
  val fixtureSettings: Seq[Def.Setting[?]] = List(
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
    },
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
  )

  // (label, source body, expected diagnostic fragment - empty means it must compile cleanly)
  private def nativeEscapes = List(
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

  private def effectEscapes = List(
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
    (
      "POSITIVE: a copy outlives the borrow",
      "val x: Eff[Nothing, List[Byte]] = acquire.wiping(v => Eff.succeed(v.drop(1).toArray.toList))",
      ""
    )
  )

  private def nativeEscapeSource(body: String) =
    s"""package boilerplate
       |import scala.language.experimental.captureChecking
       |import scala.scalanative.unsafe.*
       |object CaptureEscape:
       |  $body
       |""".stripMargin

  private def effectEscapeSource(body: String) =
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
  private def compileEscape(loader: ClassLoader, classpath: String, dest: File, source: File): String =
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

  private def assertEscape(label: String, expected: String, output: String): Option[String] =
    if expected.isEmpty then if output.trim.isEmpty then None else Some(s"  - $label: expected a clean compile, got:\n${output.take(600)}")
    else if output.trim.isEmpty then Some(s"  - $label: expected the diagnostic to contain '$expected', but the compiler reported nothing")
    else if output.contains(expected) then None
    else Some(s"  - $label: expected the diagnostic to contain '$expected', got:\n${output.take(600)}")

end BoilerplateBuild
