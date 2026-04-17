package diagrams

import scala.meta._
import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable
import scala.jdk.CollectionConverters._

case class ParsedClass(
  fqName: String,
  pkgName: String,
  pumlDef: String,           // Full class definitions with methods/fields
  pumlEmptyDef: String       // Just 'class "X" as Y'
)

object PumlGenerator {
  def main(args: Array[String]): Unit = {
    val srcDir = Paths.get("src/main/scala")
    val outDir = Paths.get("diagrams/generated")
    
    if (!Files.exists(srcDir)) {
      println(s"Source directory not found: $srcDir")
      sys.exit(1)
    }
    Files.createDirectories(outDir)

    val allFiles = Files.walk(srcDir)
      .iterator().asScala
      .filter(Files.isRegularFile(_))
      .filter(_.toString.endsWith(".scala"))
      .toSeq

    val knownFqNames = mutable.Set[String]()
    val simpleToFqName = mutable.Map[String, String]()

    println("Parsing files with Scalameta...")

    // Phase 1: Register all types
    for (file <- allFiles) {
      val source = new String(Files.readAllBytes(file), "UTF-8")
      val tree = dialects.Scala3(source).parse[Source].get

      var pkgName = ""
      def traverse1(t: Tree): Unit = t match {
        case pkg: Pkg =>
          pkgName = pkg.ref.syntax
          pkg.children.foreach(traverse1)
        case cls: Defn.Class =>
          val name = cls.name.value
          val fqName = if (pkgName.nonEmpty) s"$pkgName.$name" else name
          knownFqNames.add(fqName)
          simpleToFqName(name) = fqName
          cls.children.foreach(traverse1)
        case trt: Defn.Trait =>
          val name = trt.name.value
          val fqName = if (pkgName.nonEmpty) s"$pkgName.$name" else name
          knownFqNames.add(fqName)
          simpleToFqName(name) = fqName
          trt.children.foreach(traverse1)
        case obj: Defn.Object =>
          val name = obj.name.value
          val fqName = if (pkgName.nonEmpty) s"$pkgName.$name" else name
          knownFqNames.add(fqName)
          simpleToFqName(name) = fqName
          obj.children.foreach(traverse1)
        case enm: Defn.Enum =>
          val name = enm.name.value
          val fqName = if (pkgName.nonEmpty) s"$pkgName.$name" else name
          knownFqNames.add(fqName)
          simpleToFqName(name) = fqName
          enm.children.foreach(traverse1)
        case other =>
          other.children.foreach(traverse1)
      }
      traverse1(tree)
    }

    def visibility(mods: List[Mod]): String = {
      if (mods.exists(_.is[Mod.Private])) "-"
      else if (mods.exists(_.is[Mod.Protected])) "#"
      else "+"
    }
    
    val parsedClasses = mutable.ListBuffer[ParsedClass]()
    val inheritance = mutable.ListBuffer[(String, String)]() // (parentFq, childFq)
    val dependencies = mutable.ListBuffer[(String, String)]() // (sourceFq, targetFq)

    def extractDependencies(sourceFq: String, typeString: String): Unit = {
      // Find any words in the type that match our known simple names
      val words = "[A-Za-z0-9_]+".r.findAllIn(typeString).toList
      words.foreach { w =>
        simpleToFqName.get(w).foreach { targetFq =>
          if (sourceFq != targetFq) {
            dependencies.append((sourceFq, targetFq))
          }
        }
      }
    }

    // Phase 2: Extract details
    for (file <- allFiles) {
      val source = new String(Files.readAllBytes(file), "UTF-8")
      val tree = dialects.Scala3(source).parse[Source].get

      var pkgName = ""
      def traverse2(t: Tree): Unit = t match {
        case pkg: Pkg =>
          pkgName = pkg.ref.syntax
          pkg.children.foreach(traverse2)

        case cls: Defn.Class =>
          val fqName = if (pkgName.nonEmpty) s"$pkgName.${cls.name.value}" else cls.name.value
          val alias = fqName.replace(".", "_")
          val isCase = cls.mods.exists(_.is[Mod.Case])
          val header = if (isCase) s"class \"$fqName\" as $alias << (C,#FFDDDD) case >>" else s"class \"$fqName\" as $alias"
          
          val sb = new StringBuilder
          sb.append(s"$header {\n")
          
          cls.ctor.paramss.flatten.foreach { param =>
            val tpeStr = param.decltpe.map(_.syntax).getOrElse("Any")
            extractDependencies(fqName, tpeStr)
            val vis = if (isCase) "+" else visibility(param.mods)
            sb.append(s"  $vis ${param.name.syntax}: $tpeStr\n")
          }
          
          cls.templ.stats.foreach {
            case d: Defn.Def =>
              val mods = visibility(d.mods)
              if (mods != "-") {
                val tpeStr = d.decltpe.map(_.syntax).getOrElse("Any")
                extractDependencies(fqName, tpeStr)
                d.paramss.flatten.foreach { p =>
                  extractDependencies(fqName, p.decltpe.map(_.syntax).getOrElse("Any"))
                }
                val params = d.paramss.flatten.map(p => s"${p.name.syntax}: ${p.decltpe.map(_.syntax).getOrElse("Any")}").mkString(", ")
                sb.append(s"  $mods ${d.name.syntax}($params): $tpeStr\n")
              }
            case v: Defn.Val =>
              val mods = visibility(v.mods)
              if (mods != "-") {
                val tpeStr = v.decltpe.map(_.syntax).getOrElse("Any")
                extractDependencies(fqName, tpeStr)
                sb.append(s"  $mods ${v.pats.map(_.syntax).mkString(", ")}: $tpeStr\n")
              }
            case v: Defn.Var =>
              val mods = visibility(v.mods)
              if (mods != "-") {
                val tpeStr = v.decltpe.map(_.syntax).getOrElse("Any")
                extractDependencies(fqName, tpeStr)
                sb.append(s"  $mods ${v.pats.map(_.syntax).mkString(", ")}: $tpeStr\n")
              }
            case _ =>
          }
          sb.append("}\n")
          
          parsedClasses.append(ParsedClass(fqName, pkgName, sb.toString, header))
          
          cls.templ.inits.foreach { init =>
            val parentType = init.tpe.syntax.split("\\[").head
            simpleToFqName.get(parentType).foreach { parentFq =>
              inheritance.append((parentFq, fqName))
            }
          }
          cls.children.foreach(traverse2)

        case trt: Defn.Trait =>
          val fqName = if (pkgName.nonEmpty) s"$pkgName.${trt.name.value}" else trt.name.value
          val alias = fqName.replace(".", "_")
          val header = s"interface \"$fqName\" as $alias"
          val sb = new StringBuilder
          sb.append(s"$header {\n")
          
          trt.templ.stats.foreach {
            case d: Decl.Def =>
              val tpeStr = d.decltpe.syntax
              extractDependencies(fqName, tpeStr)
              d.paramss.flatten.foreach { p => extractDependencies(fqName, p.decltpe.map(_.syntax).getOrElse("Any")) }
              val params = d.paramss.flatten.map(p => s"${p.name.syntax}: ${p.decltpe.map(_.syntax).getOrElse("Any")}").mkString(", ")
              sb.append(s"  + ${d.name.syntax}($params): $tpeStr\n")
            case d: Defn.Def =>
              val tpeStr = d.decltpe.map(_.syntax).getOrElse("Any")
              val params = d.paramss.flatten.map(p => s"${p.name.syntax}: ${p.decltpe.map(_.syntax).getOrElse("Any")}").mkString(", ")
              extractDependencies(fqName, tpeStr)
              d.paramss.flatten.foreach { p => extractDependencies(fqName, p.decltpe.map(_.syntax).getOrElse("Any")) }
              sb.append(s"  + ${d.name.syntax}($params): $tpeStr\n")
            case v: Decl.Val =>
              val tpeStr = v.decltpe.syntax
              extractDependencies(fqName, tpeStr)
              sb.append(s"  + ${v.pats.map(_.syntax).mkString(", ")}: $tpeStr\n")
            case _ =>
          }
          sb.append("}\n")
          
          parsedClasses.append(ParsedClass(fqName, pkgName, sb.toString, header))
          
          trt.templ.inits.foreach { init =>
            val parentType = init.tpe.syntax.split("\\[").head
            simpleToFqName.get(parentType).foreach { parentFq =>
              inheritance.append((parentFq, fqName))
            }
          }
          trt.children.foreach(traverse2)

        case obj: Defn.Object =>
          val fqName = if (pkgName.nonEmpty) s"$pkgName.${obj.name.value}" else obj.name.value
          val alias = fqName.replace(".", "_")
          val header = s"object \"$fqName\" as $alias"
          val sb = new StringBuilder
          sb.append(s"$header {\n")
          
          obj.templ.stats.foreach {
            case d: Defn.Def =>
              val mods = visibility(d.mods)
              if (mods != "-") {
                val tpeStr = d.decltpe.map(_.syntax).getOrElse("Any")
                extractDependencies(fqName, tpeStr)
                d.paramss.flatten.foreach { p => extractDependencies(fqName, p.decltpe.map(_.syntax).getOrElse("Any")) }
                val params = d.paramss.flatten.map(p => s"${p.name.syntax}: ${p.decltpe.map(_.syntax).getOrElse("Any")}").mkString(", ")
                sb.append(s"  $mods ${d.name.syntax}($params): $tpeStr\n")
              }
            case _ =>
          }
          sb.append("}\n")
          
          parsedClasses.append(ParsedClass(fqName, pkgName, sb.toString, header))
          obj.children.foreach(traverse2)

        case enm: Defn.Enum =>
          val fqName = if (pkgName.nonEmpty) s"$pkgName.${enm.name.value}" else enm.name.value
          val alias = fqName.replace(".", "_")
          val header = s"enum \"$fqName\" as $alias"
          val sb = new StringBuilder
          sb.append(s"$header {\n")
          
          enm.templ.stats.foreach {
            case c: Defn.EnumCase =>
              sb.append(s"  ${c.name.syntax}\n")
            case d: Defn.Def =>
              val mods = visibility(d.mods)
              if (mods != "-") {
                val tpeStr = d.decltpe.map(_.syntax).getOrElse("Any")
                extractDependencies(fqName, tpeStr)
                d.paramss.flatten.foreach { p => extractDependencies(fqName, p.decltpe.map(_.syntax).getOrElse("Any")) }
                val params = d.paramss.flatten.map(p => s"${p.name.syntax}: ${p.decltpe.map(_.syntax).getOrElse("Any")}").mkString(", ")
                sb.append(s"  $mods ${d.name.syntax}($params): $tpeStr\n")
              }
            case _ =>
          }
          sb.append("}\n")
          parsedClasses.append(ParsedClass(fqName, pkgName, sb.toString, header))
          enm.children.foreach(traverse2)

        case other =>
          other.children.foreach(traverse2)
      }
      traverse2(tree)
    }

    // Output Generation
    def writePuml(filename: String, classes: Seq[ParsedClass], hideMembers: Boolean, writeDeps: Boolean, filterRelationsFn: (String, String) => Boolean): Unit = {
      val fileTarget = outDir.resolve(filename)
      val pumlContent = new mutable.StringBuilder()
      pumlContent.append("@startuml\n")
      pumlContent.append("skinparam shadowing false\n")
      pumlContent.append("skinparam linetype ortho\n")
      if (hideMembers) pumlContent.append("hide members\n")
      else pumlContent.append("hide empty members\n")
      pumlContent.append("\n")

      // Group by packages
      val byPkg = classes.groupBy(_.pkgName)

      for ((pkg, pkClasses) <- byPkg) {
        if (pkg.nonEmpty) pumlContent.append(s"package $pkg {\n")
        pkClasses.foreach { c =>
          if (hideMembers) pumlContent.append(c.pumlEmptyDef).append("\n")
          else pumlContent.append(c.pumlDef).append("\n")
        }
        if (pkg.nonEmpty) pumlContent.append(s"}\n\n")
      }

      val classSet = classes.map(_.fqName).toSet

      pumlContent.append("\n' Inheritance\n")
      inheritance.distinct.foreach { case (parentFq, childFq) =>
        if (classSet.contains(childFq) && filterRelationsFn(parentFq, childFq)) {
          // If parent is not printed, plantuml will just create an empty box for it
          pumlContent.append(s"${parentFq.replace(".", "_")} <|-- ${childFq.replace(".", "_")}\n")
        }
      }

      if (writeDeps) {
        pumlContent.append("\n' Dependencies\n")
        dependencies.distinct.foreach { case (srcFq, tgtFq) =>
          if (classSet.contains(srcFq) && filterRelationsFn(srcFq, tgtFq)) {
            pumlContent.append(s"${srcFq.replace(".", "_")} --> ${tgtFq.replace(".", "_")}\n")
          }
        }
      }

      pumlContent.append("@enduml\n")
      Files.write(fileTarget, pumlContent.toString().getBytes("UTF-8"))
      println(s"Generated PUML at $fileTarget")
    }

    // 1. Full architecture diagram (Original style)
    writePuml("scala-architecture.puml", parsedClasses.toSeq, hideMembers = false, writeDeps = false, (_, _) => true)

    // 2. High-level MVC Workflow (All classes, hide members, show global cross-dependencies)
    writePuml("mvc-workflow.puml", parsedClasses.toSeq, hideMembers = true, writeDeps = true, (_, _) => true)

    // 3. Package-specific detailed diagrams (Show members + deps within package or connected to package)
    val pkgs = parsedClasses.map(_.pkgName).distinct
    for (pkg <- pkgs.filter(_.nonEmpty)) {
      val inPkg = parsedClasses.filter(_.pkgName == pkg).toSeq
      val safeName = pkg.replace(".", "_")
      // Filter relation: only draw if the source is in this package. External targets will visually link outside the box.
      val inPkgSet = inPkg.map(_.fqName).toSet
      writePuml(
        s"pkg_${safeName}.puml", 
        inPkg, 
        hideMembers = false, 
        writeDeps = true, 
        filterRelationsFn = (src, tgt) => inPkgSet.contains(src) || inPkgSet.contains(tgt)
      )
    }
  }
}
