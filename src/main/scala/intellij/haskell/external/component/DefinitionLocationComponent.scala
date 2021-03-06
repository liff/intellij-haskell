/*
 * Copyright 2014-2018 Rik van der Kleij
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package intellij.haskell.external.component

import com.github.blemale.scaffeine.{AsyncLoadingCache, Scaffeine}
import com.intellij.openapi.application._
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.util.WaitFor
import intellij.haskell.external.repl.StackRepl.StackReplOutput
import intellij.haskell.external.repl.{ProjectStackRepl, StackReplsManager}
import intellij.haskell.navigation.HaskellReference
import intellij.haskell.psi._
import intellij.haskell.util.index.HaskellModuleNameIndex
import intellij.haskell.util.{ApplicationUtil, _}

import scala.concurrent.{Await, Future}

private[component] object DefinitionLocationComponent {
  private final val LocAtPattern = """(.+)\:\(([\d]+),([\d]+)\)-\(([\d]+),([\d]+)\)""".r
  private final val PackageModulePattern = """.+\:([\w\.\-]+)""".r

  private case class Key(psiFile: PsiFile, moduleName: Option[String], qualifiedNameElement: HaskellQualifiedNameElement)

  type DefinitionLocationResult = Either[NoInfo, DefinitionLocation]

  private final val Cache: AsyncLoadingCache[Key, DefinitionLocationResult] = Scaffeine().buildAsync((k: Key) => findDefinitionLocationResult(k))

  def findDefinitionLocation(psiFile: PsiFile, qualifiedNameElement: HaskellQualifiedNameElement): DefinitionLocationResult = {
    find(psiFile, qualifiedNameElement)
  }

  def findReferencesInCache(targetFile: PsiFile): Seq[(PsiFile, HaskellQualifiedNameElement)] = {
    Cache.synchronous().asMap().filter { case (k, v) => v.toOption.exists(l => {
      if (!l.namedElement.isValid) {
        Cache.synchronous().invalidate(k)
        false
      } else {
        l.namedElement.getContainingFile.getOriginalFile == targetFile
      }
    })
    }.keys.map(k => (k.psiFile, k.qualifiedNameElement)).toSeq
  }

  def invalidate(elements: Seq[HaskellQualifiedNameElement]): Unit = {
    val keys = Cache.synchronous().asMap().keys.filter(k => elements.contains(k.qualifiedNameElement))
    Cache.synchronous().invalidateAll(keys)
  }

  def invalidate(psiFile: PsiFile): Unit = {
    val keys = Cache.synchronous().asMap().filter(_._1.psiFile == psiFile).flatMap { case (k, v) =>
      v.toOption match {
        case Some(definitionLocation) =>
          if (ApplicationUtil.runReadAction(k.qualifiedNameElement.isValid) &&
            ApplicationUtil.runReadAction(definitionLocation.namedElement.isValid) &&
            ApplicationUtil.runReadAction(k.qualifiedNameElement.getName) == ApplicationUtil.runReadAction(definitionLocation.namedElement.getName)) {
            None
          } else {
            Some(k)
          }
        case None => Some(k)
      }
    }

    Cache.synchronous().invalidateAll(keys)

    val otherFileKeys = Cache.synchronous().asMap().flatMap { case (k, v) =>
      v.toOption match {
        case Some(definitionLocation) =>
          if (ApplicationUtil.runReadAction(definitionLocation.namedElement.isValid) &&
            ApplicationUtil.runReadAction(k.qualifiedNameElement.isValid) &&
            definitionLocation.namedElement.getContainingFile.getOriginalFile == psiFile &&
            ApplicationUtil.runReadAction(k.qualifiedNameElement.getName) == ApplicationUtil.runReadAction(definitionLocation.namedElement.getName)) {
            None
          } else {
            Some(k)
          }
        case None => None
      }
    }

    Cache.synchronous().invalidateAll(otherFileKeys)
  }

  def invalidateAll(project: Project): Unit = {
    Cache.synchronous().asMap().filter(_._1.psiFile.getProject == project).keys.foreach(Cache.synchronous.invalidate)
  }

  private def findDefinitionLocationResult(key: Key): DefinitionLocationResult = {
    val psiFile = key.psiFile
    val project = psiFile.getProject
    val identifierElement = key.qualifiedNameElement.getIdentifierElement
    val name = ApplicationUtil.runInReadActionWithWriteActionPriority(project, {
      if (key.qualifiedNameElement.isValid) {
        ProgressManager.checkCanceled()
        Right(identifierElement.getName)
      }
      else {
        Left(NoInfoAvailable(s"Invalid element: ${key.qualifiedNameElement.getName}", psiFile.getName))
      }
    }, "getName and check if PSI element is valid")

    name match {
      case Left(noInfo) => Left(noInfo)
      case Right(r) => r match {
        case Left(noInfo) => Left(noInfo)
        case Right(n) =>
          if (n.headOption.exists(_.isUpper)) {
            createDefinitionLocationResult(project, psiFile, key, n, withoutLastColumn = true)
          } else {
            createDefinitionLocationResult(project, psiFile, key, n, withoutLastColumn = false)
          }
      }
    }
  }

  private def createDefinitionLocationResult(project: Project, psiFile: PsiFile, key: Key, name: String, withoutLastColumn: Boolean): DefinitionLocationResult = {
    findLocationInfoWithRepl(project, psiFile, key, name, withoutLastColumn) match {
      case Right(o) => o.stdoutLines.headOption.map(l => createDefinitionLocationResultFromLocationInfo(project, psiFile, l, key, name)) match {
        case Some(r) => r
        case None => Left(NoInfoAvailable(name, key.psiFile.getName))
      }
      case Left(noInfo) => Left(noInfo)
    }
  }

  private def findLocationInfoWithRepl(project: Project, psiFile: PsiFile, key: Key, name: String, withoutLastColumn: Boolean): Either[NoInfo, StackReplOutput] = {
    val qualifiedNameElement = key.qualifiedNameElement
    val findLocation = for {
      vf <- HaskellFileUtil.findVirtualFile(psiFile)
      sp <- LineColumnPosition.fromOffset(vf, qualifiedNameElement.getTextRange.getStartOffset)
      ep <- LineColumnPosition.fromOffset(vf, qualifiedNameElement.getTextRange.getEndOffset)
      endColumnNr = if (withoutLastColumn) ep.columnNr - 1 else ep.columnNr
    } yield {
      repl: ProjectStackRepl => repl.findLocationInfo(key.moduleName, psiFile, sp.lineNr, sp.columnNr, ep.lineNr, endColumnNr, name)
    }

    findLocation match {
      case None => Left(NoInfoAvailable(name, psiFile.getContainingFile.getName))
      case Some(f) =>
        StackReplsManager.getProjectRepl(psiFile) match {
          case Some(repl) =>
            if (repl.isBusy) {
              Left(ReplIsBusy)
            } else {
              f(repl) match {
                case Some(o) if o.stderrLines.isEmpty => Right(o)
                case _ => Left(ReplNotAvailable)
              }
            }
          case None => Left(ReplNotAvailable)
        }
    }
  }

  private def createDefinitionLocationResultFromLocationInfo(project: Project, psiFile: PsiFile, output: String, key: Key, name: String): DefinitionLocationResult = {
    output match {
      case LocAtPattern(filePath, startLineNr, startColumnNr, _, _) =>
        HaskellProjectUtil.findFile(filePath, project) match {
          case (Some(vf), Right(Some(pf))) =>
            ApplicationUtil.runInReadActionWithWriteActionPriority(project,
              HaskellReference.findIdentifierByLocation(project, vf, pf, startLineNr.toInt, startColumnNr.toInt, name), s"findIdentifierByLocation for $name of file ${psiFile.getName}"
            ) match {
              case Right(Some(e)) => Right(LocalModuleLocation(pf, e))
              case Right(_) => Left(NoInfoAvailable(name, psiFile.getName))
              case Left(noInfo) => Left(noInfo)
            }
          case (_, Right(_)) => Left(NoInfoAvailable(name, psiFile.getName))
          case (_, Left(noInfo)) => Left(noInfo)
        }
      case PackageModulePattern(mn) =>
        HaskellModuleNameIndex.findFileByModuleName(project, mn) match {
          case Right(pf) =>
            ApplicationUtil.runInReadActionWithWriteActionPriority(project,
              pf.flatMap(HaskellReference.findIdentifierInFileByName(_, name)), s"findIdentifierInFileByName for $name in module $mn"
            ) match {
              case Right(ne) => ne match {
                case Some(e) => Right(PackageModuleLocation(mn, e))
                case None => Left(NoInfoAvailable(name, key.psiFile.getName))
              }
              case Left(noInfo) => Left(noInfo)

            }
          case Left(noInfo) => Left(noInfo)
        }
      case _ => Left(NoInfoAvailable(name, key.psiFile.getName))
    }
  }

  import scala.concurrent.duration._

  private[component] def find(psiFile: PsiFile, qualifiedNameElement: HaskellQualifiedNameElement): DefinitionLocationResult = {
    def wait(f: => Future[DefinitionLocationResult]): DefinitionLocationResult = {

      // This timeout is not used by dispatch thread so can be relatively long
      // Same timeout as REPL, so give enough time to start REPL
      val wf = new WaitFor(5000, 1) {
        override def condition(): Boolean = {
          ProgressManager.checkCanceled()
          f.isCompleted
        }
      }

      if (wf.isConditionRealized) {
        Await.result(f, 1.milli)
      } else {
        Left(ReplNotAvailable)
      }
    }

    ProgressManager.checkCanceled()

    val moduleName = HaskellPsiUtil.findModuleName(psiFile)

    ProgressManager.checkCanceled()

    val key = Key(psiFile, moduleName, qualifiedNameElement)

    ProgressManager.checkCanceled()

    val isDispatchThread = ApplicationManager.getApplication.isDispatchThread

    ProgressManager.checkCanceled()

    if (isDispatchThread && !LoadComponent.isModuleLoaded(moduleName, psiFile)) {
      Left(ModuleNotLoaded(psiFile.getName))
    } else {
      if (isDispatchThread) {
        Cache.synchronous.getIfPresent(key) match {
          case Some(result) =>
            result match {
              case Right(location) =>
                if (location.namedElement.isValid &&
                  key.qualifiedNameElement.isValid &&
                  key.qualifiedNameElement.getIdentifierElement.getName == location.namedElement.getName) {
                  result
                } else {
                  Cache.synchronous.invalidate(key)
                  Left(NoInfoAvailable(s"Invalid element: ${location.namedElement.getName}", psiFile.getName))
                }
              case Left(_) => result
            }
          case None =>
            val result = findDefinitionLocationResult(key)
            result match {
              case Right(_) =>
                ApplicationManager.getApplication.executeOnPooledThread(ScalaUtil.runnable {
                  Cache.synchronous.put(key, result)
                })
                result
              case Left(ReplIsBusy) | Left(ReadActionTimeout(_)) | Left(IndexNotReady) | Left(ReplNotAvailable) =>
                Cache.synchronous.invalidate(key)
                result
              case l@Left(_) => l
            }
        }
      }
      else {
        ProgressManager.checkCanceled()
        val result = wait(Cache.get(key))
        result match {
          case Right(location) =>
            if (ApplicationUtil.runReadAction(location.namedElement.isValid) &&
              ApplicationUtil.runReadAction(key.qualifiedNameElement.isValid) &&
              ApplicationUtil.runReadAction(key.qualifiedNameElement.getIdentifierElement.getName) == ApplicationUtil.runReadAction(location.namedElement.getName)) {
              result
            } else {
              Cache.synchronous.invalidate(key)
              Left(NoInfoAvailable(s"Invalid element:  ${ApplicationUtil.runReadAction(location.namedElement.getName)}", psiFile.getName))
            }
          case Left(ReplIsBusy) | Left(ReadActionTimeout(_)) | Left(IndexNotReady) =>
            Cache.synchronous().invalidate(key)
            result
          case _ => result
        }
      }
    }
  }
}

sealed trait DefinitionLocation {
  def namedElement: HaskellNamedElement
}

case class PackageModuleLocation(moduleName: String, namedElement: HaskellNamedElement) extends DefinitionLocation

case class LocalModuleLocation(psiFile: PsiFile, namedElement: HaskellNamedElement) extends DefinitionLocation

