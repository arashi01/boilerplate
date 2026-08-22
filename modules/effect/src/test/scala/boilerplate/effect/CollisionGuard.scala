/*
 * Copyright (c) 2025, 2026 Boilerplate contributors.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package boilerplate.effect

import scala.quoted.*

// A lexical-scope implicit conversion is searched before the receiver's implicit scope, so a
// combinator declared only in `Eff`'s companion is captured by any imported cats or cats-effect
// conversion that names it - which resolves one `F` for both operands and softens the union channel,
// or fails outright where our signature differs. The twin set is what prevents that, and this
// enumeration derives, from the classpath rather than from a written list, which names still need one.
object CollisionGuard:

  /** The conversion-target names, the collisions against our surface, the declared twins, and the
    * collisions with no twin.
    */
  inline def enumerate: (List[String], List[String], List[String], List[String]) = ${ enumerateImpl }

  private def enumerateImpl(using Quotes): Expr[(List[String], List[String], List[String], List[String])] =
    import quotes.reflect.*

    def resultOf(tpe: TypeRepr): TypeRepr = tpe match
      case PolyType(_, _, res)    => resultOf(res)
      case MethodType(_, _, res)  => resultOf(res)
      case ByNameType(underlying) => resultOf(underlying)
      case other                  => other

    def usable(symbol: Symbol): Boolean =
      !symbol.isNoSymbol && !symbol.flags.is(Flags.Synthetic) && !symbol.flags.is(Flags.Private) &&
        !symbol.flags.is(Flags.Protected) && symbol.name != "<init>" && !symbol.name.startsWith("$")

    // Every method the syntax aggregator brings into scope whose result is a cats Ops class, and the
    // names that class declares - the set an import makes available on any receiver it accepts.
    def conversionTargets(module: String): List[String] =
      Symbol.requiredModule(module).methodMembers.filter(usable).flatMap { conversion =>
        val target = resultOf(conversion.termRef.widen).typeSymbol
        if target.isClassDef && target.fullName.startsWith("cats.") then target.declaredMethods.filter(usable).map(_.name)
        else Nil
      }

    def extensionNames(module: String): List[String] =
      Symbol.requiredModule(module).declaredMethods.filter(s => usable(s) && s.flags.is(Flags.ExtensionMethod)).map(_.name)

    val targets = (conversionTargets("cats.syntax.all") ++ conversionTargets("cats.effect.syntax.all")).distinct.sorted
    val ours = (extensionNames("boilerplate.effect.Eff") ++ extensionNames("boilerplate.effect.EffResource")).distinct.sorted
    val twins = extensionNames("boilerplate.effect.syntax$package").distinct.sorted
    val collisions = ours.filter(targets.contains).sorted
    val untwinned = collisions.filterNot(twins.contains).sorted

    Expr((targets, collisions, twins, untwinned))
  end enumerateImpl
end CollisionGuard
