/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package com.intellij.configurationStore

import com.intellij.ide.highlighter.ProjectFileType
import com.intellij.ide.highlighter.WorkspaceFileType
import com.intellij.notification.Notifications
import com.intellij.notification.NotificationsManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeAndWaitIfNeed
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.*
import com.intellij.openapi.components.StateStorage.SaveSession
import com.intellij.openapi.components.impl.stores.IComponentStore
import com.intellij.openapi.components.impl.stores.IProjectStore
import com.intellij.openapi.components.impl.stores.StoreUtil
import com.intellij.openapi.diagnostic.catchAndLog
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.impl.ProjectImpl
import com.intellij.openapi.project.impl.ProjectManagerImpl.UnableToSaveProjectNotification
import com.intellij.openapi.project.impl.ProjectStoreClassProvider
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.util.Pair
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.PathUtilRt
import com.intellij.util.SmartList
import com.intellij.util.attribute
import com.intellij.util.containers.forEachGuaranteed
import com.intellij.util.containers.isNullOrEmpty
import com.intellij.util.io.*
import com.intellij.util.lang.CompoundRuntimeException
import com.intellij.util.text.nullize
import org.jdom.Element
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths

const val PROJECT_FILE = "\$PROJECT_FILE$"
const val PROJECT_CONFIG_DIR = "\$PROJECT_CONFIG_DIR$"

val IProjectStore.nameFile: Path
  get() = Paths.get(projectBasePath, Project.DIRECTORY_STORE_FOLDER, ProjectImpl.NAME_FILE)

internal val PROJECT_FILE_STORAGE_ANNOTATION = FileStorageAnnotation(PROJECT_FILE, false)
internal val DEPRECATED_PROJECT_FILE_STORAGE_ANNOTATION = FileStorageAnnotation(PROJECT_FILE, true)

abstract class ProjectStoreBase(override final val project: ProjectImpl) : ComponentStoreImpl(), IProjectStore {
  // protected setter used in upsource
  // Zelix KlassMaster - ERROR: Could not find method 'getScheme()'
  var scheme = StorageScheme.DEFAULT

  override final var loadPolicy = StateLoadPolicy.LOAD

  override final fun isOptimiseTestLoadSpeed() = loadPolicy != StateLoadPolicy.LOAD

  override final fun getStorageScheme() = scheme

  override abstract val storageManager: StateStorageManagerImpl

  protected val isDirectoryBased: Boolean
    get() = scheme == StorageScheme.DIRECTORY_BASED

  override final fun setOptimiseTestLoadSpeed(value: Boolean) {
    // we don't load default state in tests as app store does because
    // 1) we should not do it
    // 2) it was so before, so, we preserve old behavior (otherwise RunManager will load template run configurations)
    loadPolicy = if (value) StateLoadPolicy.NOT_LOAD else StateLoadPolicy.LOAD
  }

  override fun getProjectFilePath() = storageManager.expandMacro(PROJECT_FILE)

  override final fun getWorkspaceFilePath() = storageManager.expandMacro(StoragePathMacros.WORKSPACE_FILE)

  override final fun clearStorages() {
    storageManager.clearStorages()
  }

  override final fun loadProjectFromTemplate(defaultProject: Project) {
    defaultProject.save()

    val element = (defaultProject.stateStore as DefaultProjectStoreImpl).getStateCopy() ?: return
    LOG.catchAndLog {
      removeWorkspaceComponentConfiguration(defaultProject, element)
    }

    if (isDirectoryBased) {
      LOG.catchAndLog {
        for (component in element.getChildren("component")) {
          when (component.getAttributeValue("name")) {
            "InspectionProjectProfileManager" -> convertProfiles(component.getChildren("profile").iterator(), true)
            "CopyrightManager" -> convertProfiles(component.getChildren("copyright").iterator(), false)
          }
        }
      }
    }
    (storageManager.getOrCreateStorage(PROJECT_FILE) as XmlElementStorage).setDefaultState(element)
  }

  fun convertProfiles(profileIterator: MutableIterator<Element>, isInspection: Boolean) {
    for (profile in profileIterator) {
      val schemeName = profile.getChildren("option").find { it.getAttributeValue("name") == "myName" }?.getAttributeValue(
          "value") ?: continue

      profileIterator.remove()
      val wrapper = Element("component").attribute("name", if (isInspection) "InspectionProjectProfileManager" else "CopyrightManager")
      wrapper.addContent(profile)
      val path = Paths.get(storageManager.expandMacro(PROJECT_CONFIG_DIR), if (isInspection) "inspectionProfiles" else "copyright",
          "${FileUtil.sanitizeFileName(schemeName, true)}.xml")
      JDOMUtil.writeParent(wrapper, path.outputStream(), "\n")
    }
  }

  override final fun getProjectBasePath(): String {
    if (isDirectoryBased) {
      val path = PathUtilRt.getParentPath(storageManager.expandMacro(PROJECT_CONFIG_DIR))
      if (Registry.`is`("store.basedir.parent.detection", true) && path.startsWith("${Project.DIRECTORY_STORE_FOLDER}.")) {
        return PathUtilRt.getParentPath(path)
      }
      return path
    }
    else {
      return PathUtilRt.getParentPath(projectFilePath)
    }
  }

  // used in upsource
  protected fun setPath(filePath: String, refreshVfs: Boolean, useOldWorkspaceContentIfExists: Boolean) {
    val storageManager = storageManager
    val fs = LocalFileSystem.getInstance()
    if (FileUtilRt.extensionEquals(filePath, ProjectFileType.DEFAULT_EXTENSION)) {
      scheme = StorageScheme.DEFAULT

      storageManager.addMacro(PROJECT_FILE, filePath)

      val workspacePath = composeWsPath(filePath)
      storageManager.addMacro(StoragePathMacros.WORKSPACE_FILE, workspacePath)

      if (refreshVfs) {
        invokeAndWaitIfNeed {
          VfsUtil.markDirtyAndRefresh(false, true, false, fs.refreshAndFindFileByPath(filePath), fs.refreshAndFindFileByPath(workspacePath))
        }
      }

      if (ApplicationManager.getApplication().isUnitTestMode) {
        // load state only if there are existing files
        isOptimiseTestLoadSpeed = !File(filePath).exists()
      }
    }
    else {
      scheme = StorageScheme.DIRECTORY_BASED

      // if useOldWorkspaceContentIfExists false, so, file path is expected to be correct (we must avoid file io operations)
      val isDir = !useOldWorkspaceContentIfExists || Paths.get(filePath).isDirectory()
      val configDir = "${(if (isDir) filePath else PathUtilRt.getParentPath(filePath))}/${Project.DIRECTORY_STORE_FOLDER}"
      storageManager.addMacro(PROJECT_CONFIG_DIR, configDir)
      storageManager.addMacro(PROJECT_FILE, "$configDir/misc.xml")
      storageManager.addMacro(StoragePathMacros.WORKSPACE_FILE, "$configDir/workspace.xml")

      if (!isDir) {
        val workspace = File(workspaceFilePath)
        if (!workspace.exists()) {
          useOldWorkspaceContent(filePath, workspace)
        }
      }

      if (ApplicationManager.getApplication().isUnitTestMode) {
        // load state only if there are existing files
        isOptimiseTestLoadSpeed = !Paths.get(filePath).exists()
      }

      if (refreshVfs) {
        invokeAndWaitIfNeed { VfsUtil.markDirtyAndRefresh(false, true, true, fs.refreshAndFindFileByPath(configDir)) }
      }
    }
  }

  override fun <T> getStorageSpecs(component: PersistentStateComponent<T>, stateSpec: State, operation: StateStorageOperation): Array<out Storage> {
    val storages = stateSpec.storages
    if (storages.isEmpty()) {
      return arrayOf(PROJECT_FILE_STORAGE_ANNOTATION)
    }

    if (isDirectoryBased) {
      var result: MutableList<Storage>? = null
      for (storage in storages) {
        @Suppress("DEPRECATION")
        if (storage.path != PROJECT_FILE) {
          if (result == null) {
            result = SmartList()
          }
          result.add(storage)
        }
      }

      if (result.isNullOrEmpty()) {
        return arrayOf(PROJECT_FILE_STORAGE_ANNOTATION)
      }
      else {
        result!!.sortWith(deprecatedComparator)
        // if we create project from default, component state written not to own storage file, but to project file,
        // we don't have time to fix it properly, so, ancient hack restored
        result.add(DEPRECATED_PROJECT_FILE_STORAGE_ANNOTATION)
        return result.toTypedArray()
      }
    }
    else {
      var result: MutableList<Storage>? = null
      // FlexIdeProjectLevelCompilerOptionsHolder, FlexProjectLevelCompilerOptionsHolderImpl and CustomBeanRegistry
      var hasOnlyDeprecatedStorages = true
      for (storage in storages) {
        @Suppress("DEPRECATION")
        if (storage.path == PROJECT_FILE || storage.path == StoragePathMacros.WORKSPACE_FILE) {
          if (result == null) {
            result = SmartList()
          }
          result.add(storage)
          if (!storage.deprecated) {
            hasOnlyDeprecatedStorages = false
          }
        }
      }
      if (result.isNullOrEmpty()) {
        return arrayOf(PROJECT_FILE_STORAGE_ANNOTATION)
      }
      else {
        if (hasOnlyDeprecatedStorages) {
          result!!.add(PROJECT_FILE_STORAGE_ANNOTATION)
        }
        result!!.sortWith(deprecatedComparator)
        return result.toTypedArray()
      }
    }
  }

  override fun isProjectFile(file: VirtualFile): Boolean {
    if (!file.isInLocalFileSystem) {
      return false
    }

    val filePath = file.path
    if (!isDirectoryBased) {
      return filePath == projectFilePath || filePath == workspaceFilePath
    }
    return FileUtil.isAncestor(PathUtilRt.getParentPath(projectFilePath), filePath, false)
  }

  override fun getDirectoryStorePath(ignoreProjectStorageScheme: Boolean) = if (!ignoreProjectStorageScheme && !isDirectoryBased) null else PathUtilRt.getParentPath(projectFilePath).nullize()

  override fun getDirectoryStoreFile() = directoryStorePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }

  override fun getDirectoryStorePathOrBase() = PathUtilRt.getParentPath(projectFilePath)
}

private open class ProjectStoreImpl(project: ProjectImpl, private val pathMacroManager: PathMacroManager) : ProjectStoreBase(project) {
  private var lastSavedProjectName: String? = null

  init {
    assert(!project.isDefault)
  }

  override final fun getPathMacroManagerForDefaults() = pathMacroManager

  override val storageManager = ProjectStateStorageManager(pathMacroManager.createTrackingSubstitutor(), project)

  override fun setPath(filePath: String) {
    setPath(filePath, true, true)
  }

  override fun getProjectName(): String {
    if (isDirectoryBased) {
      val baseDir = projectBasePath
      val nameFile = nameFile
      if (nameFile.exists()) {
        try {
          nameFile.inputStream().reader().useLines() { it.firstOrNull { !it.isEmpty() }?.trim() }?.let {
            lastSavedProjectName = it
            return it
          }
        }
        catch (ignored: IOException) {
        }
      }

      return PathUtilRt.getFileName(baseDir).replace(":", "")
    }
    else {
      return PathUtilRt.getFileName(projectFilePath).removeSuffix(ProjectFileType.DOT_DEFAULT_EXTENSION)
    }
  }

  private fun saveProjectName() {
    if (!isDirectoryBased) {
      return
    }

    val currentProjectName = project.name
    if (lastSavedProjectName == currentProjectName) {
      return
    }

    lastSavedProjectName = currentProjectName

    val basePath = projectBasePath
    if (currentProjectName == PathUtilRt.getFileName(basePath)) {
      // name equals to base path name - just remove name
      nameFile.delete()
    }
    else {
      if (Paths.get(basePath).isDirectory()) {
        nameFile.write(currentProjectName.toByteArray())
      }
    }
  }

  override fun doSave(saveSessions: List<SaveSession>, readonlyFiles: MutableList<Pair<SaveSession, VirtualFile>>, prevErrors: MutableList<Throwable>?): MutableList<Throwable>? {
    try {
      saveProjectName()
    }
    catch (e: Throwable) {
      LOG.error("Unable to store project name", e)
    }

    var errors = prevErrors
    beforeSave(readonlyFiles)

    errors = super.doSave(saveSessions, readonlyFiles, errors)

    val notifications = NotificationsManager.getNotificationsManager().getNotificationsOfType(UnableToSaveProjectNotification::class.java, project)
    if (readonlyFiles.isEmpty()) {
      for (notification in notifications) {
        notification.expire()
      }
      return errors
    }

    if (!notifications.isEmpty()) {
      throw IComponentStore.SaveCancelledException()
    }

    val status = runReadAction { ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(*getFilesList(readonlyFiles)) }
    if (status.hasReadonlyFiles()) {
      dropUnableToSaveProjectNotification(project, status.readonlyFiles)
      throw IComponentStore.SaveCancelledException()
    }

    val oldList = readonlyFiles.toTypedArray()
    readonlyFiles.clear()
    for (entry in oldList) {
      errors = executeSave(entry.first, readonlyFiles, errors)
    }

    CompoundRuntimeException.throwIfNotEmpty(errors)

    if (!readonlyFiles.isEmpty()) {
      dropUnableToSaveProjectNotification(project, getFilesList(readonlyFiles))
      throw IComponentStore.SaveCancelledException()
    }

    return errors
  }

  protected open fun beforeSave(readonlyFiles: List<Pair<SaveSession, VirtualFile>>) {
  }
}

private fun dropUnableToSaveProjectNotification(project: Project, readOnlyFiles: Array<VirtualFile>) {
  val notifications = NotificationsManager.getNotificationsManager().getNotificationsOfType(UnableToSaveProjectNotification::class.java, project)
  if (notifications.isEmpty()) {
    Notifications.Bus.notify(UnableToSaveProjectNotification(project, readOnlyFiles), project)
  }
  else {
    notifications[0].myFiles = readOnlyFiles
  }
}

private fun getFilesList(readonlyFiles: List<Pair<SaveSession, VirtualFile>>) = Array(readonlyFiles.size) { readonlyFiles[it].second }

private class ProjectWithModulesStoreImpl(project: ProjectImpl, pathMacroManager: PathMacroManager) : ProjectStoreImpl(project, pathMacroManager) {
  override fun beforeSave(readonlyFiles: List<Pair<SaveSession, VirtualFile>>) {
    super.beforeSave(readonlyFiles)

    for (module in (ModuleManager.getInstance(project)?.modules ?: Module.EMPTY_ARRAY)) {
      module.stateStore.save(readonlyFiles)
    }
  }
}

// used in upsource
class PlatformLangProjectStoreClassProvider : ProjectStoreClassProvider {
  override fun getProjectStoreClass(isDefaultProject: Boolean): Class<out IComponentStore> {
    return if (isDefaultProject) DefaultProjectStoreImpl::class.java else ProjectWithModulesStoreImpl::class.java
  }
}

private class PlatformProjectStoreClassProvider : ProjectStoreClassProvider {
  override fun getProjectStoreClass(isDefaultProject: Boolean): Class<out IComponentStore> {
    return if (isDefaultProject) DefaultProjectStoreImpl::class.java else ProjectStoreImpl::class.java
  }
}

private fun composeWsPath(filePath: String) = "${FileUtilRt.getNameWithoutExtension(filePath)}${WorkspaceFileType.DOT_DEFAULT_EXTENSION}"

private fun useOldWorkspaceContent(filePath: String, ws: File) {
  val oldWs = File(composeWsPath(filePath))
  if (!oldWs.exists()) {
    return
  }

  try {
    FileUtil.copyContent(oldWs, ws)
  }
  catch (e: IOException) {
    LOG.error(e)
  }
}

// public only to test
fun removeWorkspaceComponentConfiguration(defaultProject: Project, element: Element) {
  val componentElements = element.getChildren("component")
  if (componentElements.isEmpty()) {
    return
  }

  @Suppress("DEPRECATION")
  val projectComponents = defaultProject.getComponents(PersistentStateComponent::class.java)
  projectComponents.forEachGuaranteed {
    val stateAnnotation = StoreUtil.getStateSpec(it.javaClass)
    if (stateAnnotation == null || stateAnnotation.name.isNullOrEmpty()) {
      return@forEachGuaranteed
    }

    val storage = stateAnnotation.storages.sortByDeprecated().firstOrNull() ?: return@forEachGuaranteed
    if (storage.path != StoragePathMacros.WORKSPACE_FILE) {
      return@forEachGuaranteed
    }

    val iterator = componentElements.iterator()
    for (componentElement in iterator) {
      if (componentElement.getAttributeValue("name") == stateAnnotation.name) {
        iterator.remove()
        break
      }
    }
  }
  return
}