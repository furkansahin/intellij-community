package org.jetbrains.plugins.gradle.util;

import com.intellij.execution.rmi.RemoteUtil;
import com.intellij.ide.actions.OpenProjectFileChooserDescriptor;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DataKey;
import com.intellij.openapi.actionSystem.DataProvider;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.externalSystem.model.project.ExternalProject;
import com.intellij.openapi.externalSystem.model.project.id.ProjectEntityId;
import com.intellij.openapi.externalSystem.ui.ProjectStructureNode;
import com.intellij.openapi.externalSystem.util.ExternalSystemBundle;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileTypeDescriptor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.ui.popup.BalloonBuilder;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.NotNullLazyValue;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.Consumer;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.gradle.config.GradleSettings;
import org.jetbrains.plugins.gradle.internal.task.GradleRefreshTasksListTask;
import org.jetbrains.plugins.gradle.internal.task.GradleResolveProjectTask;
import com.intellij.openapi.externalSystem.model.project.id.GradleSyntheticId;
import org.jetbrains.plugins.gradle.remote.GradleApiException;
import org.jetbrains.plugins.gradle.sync.GradleProjectStructureTreeModel;
import org.jetbrains.plugins.gradle.ui.GradleDataKeys;
import com.intellij.openapi.externalSystem.ui.ProjectStructureNodeDescriptor;
import org.jetbrains.plugins.gradle.ui.MatrixControlBuilder;

import javax.swing.*;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.io.*;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Holds miscellaneous utility methods.
 * 
 * @author Denis Zhdanov
 * @since 8/25/11 1:19 PM
 */
public class GradleUtil {

  public static final  String  SYSTEM_DIRECTORY_PATH_KEY    = "GRADLE_USER_HOME";
  private static final String  WRAPPER_VERSION_PROPERTY_KEY = "distributionUrl";
  private static final Pattern WRAPPER_VERSION_PATTERN      = Pattern.compile(".*gradle-(.+)-bin.zip");

  private static final NotNullLazyValue<GradleInstallationManager> INSTALLATION_MANAGER =
    new NotNullLazyValue<GradleInstallationManager>() {
      @NotNull
      @Override
      protected GradleInstallationManager compute() {
        return ServiceManager.getService(GradleInstallationManager.class);
      }
    };

  private GradleUtil() {
  }

  /**
   * Allows to retrieve file chooser descriptor that filters gradle scripts.
   * <p/>
   * <b>Note:</b> we want to fall back to the standard {@link FileTypeDescriptor} when dedicated gradle file type
   * is introduced (it's processed as groovy file at the moment). We use open project descriptor here in order to show
   * custom gradle icon at the file chooser ({@link icons.GradleIcons#Gradle}, is used at the file chooser dialog via
   * the dedicated gradle project open processor).
   */
  @NotNull
  public static FileChooserDescriptor getGradleProjectFileChooserDescriptor() {
    return DescriptorHolder.GRADLE_BUILD_FILE_CHOOSER_DESCRIPTOR;
  }

  @NotNull
  public static FileChooserDescriptor getGradleHomeFileChooserDescriptor() {
    return DescriptorHolder.GRADLE_HOME_FILE_CHOOSER_DESCRIPTOR;
  }

  @SuppressWarnings("IOResourceOpenedButNotSafelyClosed")
  public static boolean isGradleWrapperDefined(@Nullable String gradleProjectPath) {
    return !StringUtil.isEmpty(getWrapperVersion(gradleProjectPath));
  }

  /**
   * Tries to parse what gradle version should be used with gradle wrapper for the gradle project located at the given path. 
   *
   * @param gradleProjectPath  target gradle project path
   * @return gradle version should be used with gradle wrapper for the gradle project located at the given path
   *                           if any; <code>null</code> otherwise
   */
  @Nullable
  public static String getWrapperVersion(@Nullable String gradleProjectPath) {
    if (gradleProjectPath == null) {
      return null;
    }
    File file = new File(gradleProjectPath);
    if (!file.isFile()) {
      return null;
    }

    File gradleDir = new File(file.getParentFile(), "gradle");
    if (!gradleDir.isDirectory()) {
      return null;
    }

    File wrapperDir = new File(gradleDir, "wrapper");
    if (!wrapperDir.isDirectory()) {
      return null;
    }

    File[] candidates = wrapperDir.listFiles(new FileFilter() {
      @Override
      public boolean accept(File candidate) {
        return candidate.isFile() && candidate.getName().endsWith(".properties");
      }
    });
    if (candidates == null) {
      GradleLog.LOG.warn("No *.properties file is found at the gradle wrapper directory " + wrapperDir.getAbsolutePath());
      return null;
    }
    else if (candidates.length != 1) {
      GradleLog.LOG.warn(String.format(
        "%d *.properties files instead of one have been found at the wrapper directory (%s): %s",
        candidates.length, wrapperDir.getAbsolutePath(), Arrays.toString(candidates)
      ));
      return null;
    }

    Properties props = new Properties();
    BufferedReader reader = null;
    try {
      //noinspection IOResourceOpenedButNotSafelyClosed
      reader = new BufferedReader(new FileReader(candidates[0]));
      props.load(reader);
      String value = props.getProperty(WRAPPER_VERSION_PROPERTY_KEY);
      if (StringUtil.isEmpty(value)) {
        return null;
      }
      Matcher matcher = WRAPPER_VERSION_PATTERN.matcher(value);
      if (matcher.matches()) {
        return matcher.group(1);
      }
    }
    catch (IOException e) {
      GradleLog.LOG.warn(
        String.format("I/O exception on reading gradle wrapper properties file at '%s'", candidates[0].getAbsolutePath()),
        e
      );
    }
    finally {
      if (reader != null) {
        try {
          reader.close();
        }
        catch (IOException e) {
          // Ignore
        }
      }
    }
    return null;
  }

  /**
   * Asks to show balloon that contains information related to the given component.
   *
   * @param component    component for which we want to show information
   * @param messageType  balloon message type
   * @param message      message to show
   */
  public static void showBalloon(@NotNull JComponent component, @NotNull MessageType messageType, @NotNull String message) {
    final BalloonBuilder builder = JBPopupFactory.getInstance().createHtmlTextBalloonBuilder(message, messageType, null)
      .setDisposable(ApplicationManager.getApplication()).setFadeoutTime(TimeUnit.SECONDS.toMillis(1));
    Balloon balloon = builder.createBalloon();
    Dimension size = component.getSize();
    Balloon.Position position;
    int x;
    int y;
    if (size == null) {
      x = y = 0;
      position = Balloon.Position.above;
    }
    else {
      x = Math.min(10, size.width / 2);
      y = size.height;
      position = Balloon.Position.below;
    }
    balloon.show(new RelativePoint(component, new Point(x, y)), position);
  }

  /**
   * Delegates to the {@link #refreshProject(Project, String, Ref, Ref, boolean, boolean)} with the following defaults:
   * <pre>
   * <ul>
   *   <li>target gradle project path is retrieved from the {@link GradleSettings gradle settings} associated with the given project;</li>
   *   <li>refresh process is run in background;</li>
   *   <li>any problem occurred during the refresh is reported to the {@link GradleLog#LOG};</li>
   * </ul>
   * </pre>
   *
   * @param project  target intellij project to use
   */
  public static void refreshProject(@NotNull Project project) {
    refreshProject(project, new Ref<String>());
  }

  public static void refreshProject(@NotNull Project project, @NotNull final Consumer<String> errorCallback) {
    final Ref<String> errorMessageHolder = new Ref<String>() {
      @Override
      public void set(@Nullable String value) {
        if (value != null) {
          errorCallback.consume(value);
        }
      }
    };
    refreshProject(project, errorMessageHolder);
  }
  
  public static void refreshProject(@NotNull Project project, @NotNull final Ref<String> errorMessageHolder) {
    final GradleSettings settings = GradleSettings.getInstance(project);
    final String linkedProjectPath = settings.getLinkedProjectPath();
    if (StringUtil.isEmpty(linkedProjectPath)) {
      return;
    }
    assert linkedProjectPath != null;
    Ref<String> errorDetailsHolder = new Ref<String>() {
      @Override
      public void set(@Nullable String error) {
        if (!StringUtil.isEmpty(error)) {
          assert error != null;
          GradleLog.LOG.warn(error);
        }
      }
    };
    refreshProject(project, linkedProjectPath, errorMessageHolder, errorDetailsHolder, true, false);
  }

  /**
   * {@link RemoteUtil#unwrap(Throwable) unwraps} given exception if possible and builds error message for it.
   *
   * @param e  exception to process
   * @return   error message for the given exception
   */
  @SuppressWarnings({"ThrowableResultOfMethodCallIgnored", "IOResourceOpenedButNotSafelyClosed"})
  @NotNull
  public static String buildErrorMessage(@NotNull Throwable e) {
    Throwable unwrapped = RemoteUtil.unwrap(e);
    String reason = unwrapped.getLocalizedMessage();
    if (!StringUtil.isEmpty(reason)) {
      return reason;
    }
    else if (unwrapped.getClass() == GradleApiException.class) {
      return String.format("gradle api threw an exception: %s", ((GradleApiException)unwrapped).getOriginalReason());
    }
    else {
      StringWriter writer = new StringWriter();
      unwrapped.printStackTrace(new PrintWriter(writer));
      return writer.toString();
    }
  }

  @SuppressWarnings("ThrowableResultOfMethodCallIgnored")
  @Nullable
  private static String extractDetails(@NotNull Throwable e) {
    final Throwable unwrapped = RemoteUtil.unwrap(e);
    if (unwrapped instanceof GradleApiException) {
      return ((GradleApiException)unwrapped).getOriginalReason();
    }
    return null;
  }
  
  /**
   * Queries slave gradle process to refresh target gradle project.
   * 
   * @param project            target intellij project to use
   * @param gradleProjectPath  path of the target gradle project's file
   * @param errorMessageHolder holder for the error message that describes a problem occurred during the refresh (if any)
   * @param errorDetailsHolder holder for the error details of the problem occurred during the refresh (if any)
   * @param resolveLibraries   flag that identifies whether gradle libraries should be resolved during the refresh
   * @return                   the most up-to-date gradle project (if any)
   */
  @Nullable
  public static ExternalProject refreshProject(@NotNull final Project project,
                                             @NotNull final String gradleProjectPath,
                                             @NotNull final Ref<String> errorMessageHolder,
                                             @NotNull final Ref<String> errorDetailsHolder,
                                             final boolean resolveLibraries,
                                             final boolean modal)
  {
    final Ref<ExternalProject> gradleProject = new Ref<ExternalProject>();
    final TaskUnderProgress refreshProjectStructureTask = new TaskUnderProgress() {
      @SuppressWarnings({"ThrowableResultOfMethodCallIgnored", "IOResourceOpenedButNotSafelyClosed"})
      @Override
      public void execute(@NotNull ProgressIndicator indicator) {
        GradleResolveProjectTask task = new GradleResolveProjectTask(project, gradleProjectPath, resolveLibraries);
        task.execute(indicator);
        gradleProject.set(task.getGradleProject());
        final Throwable error = task.getError();
        if (error == null) {
          return;
        }
        final String message = buildErrorMessage(error);
        if (StringUtil.isEmpty(message)) {
          errorMessageHolder.set(String.format("Can't resolve gradle project at '%s'. Reason: %s", gradleProjectPath, message));
        }
        else {
          errorMessageHolder.set(message);
        }
        errorDetailsHolder.set(extractDetails(error));
      }
    };
    
    final TaskUnderProgress refreshTasksTask = new TaskUnderProgress() {
      @Override
      public void execute(@NotNull ProgressIndicator indicator) {
        final GradleRefreshTasksListTask task = new GradleRefreshTasksListTask(project, gradleProjectPath);
        task.execute(indicator);
      }
    };
    
    UIUtil.invokeAndWaitIfNeeded(new Runnable() {
      @Override
      public void run() {
        if (modal) {
          ProgressManager.getInstance().run(new Task.Modal(project, ExternalSystemBundle.message("gradle.import.progress.text"), false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
              refreshProjectStructureTask.execute(indicator);
              setTitle(ExternalSystemBundle.message("gradle.task.progress.initial.text"));
              refreshTasksTask.execute(indicator);
            }
          });
        }
        else {
          ProgressManager.getInstance().run(new Task.Backgroundable(project, ExternalSystemBundle
            .message("gradle.sync.progress.initial.text")) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
              refreshProjectStructureTask.execute(indicator);
              setTitle(ExternalSystemBundle.message("gradle.task.progress.initial.text"));
              refreshTasksTask.execute(indicator);
            }
          });
        }
      }
    });
    return gradleProject.get();
  }
  
  @NotNull
  public static <T extends ProjectEntityId> ProjectStructureNodeDescriptor<T> buildDescriptor(@NotNull T id, @NotNull String name) {
    return new ProjectStructureNodeDescriptor<T>(id, name, id.getType().getIcon());
  }
  
  @NotNull
  public static ProjectStructureNodeDescriptor<GradleSyntheticId> buildSyntheticDescriptor(@NotNull String text) {
    return buildSyntheticDescriptor(text, null);
  }
  
  public static ProjectStructureNodeDescriptor<GradleSyntheticId> buildSyntheticDescriptor(@NotNull String text, @Nullable Icon icon) {
    return new ProjectStructureNodeDescriptor<GradleSyntheticId>(new GradleSyntheticId(text), text, icon);
  }

  /**
   * Tries to calculate the position to use for showing hint for the given node of the given tree.
   * 
   * @param node  target node for which a hint should be shown
   * @param tree  target tree that contains given node
   * @return      preferred hint position (in coordinates relative to the given tree) if it's possible to calculate the one;
   *              <code>null</code> otherwise
   */
  @Nullable
  public static Point getHintPosition(@NotNull ProjectStructureNode<?> node, @NotNull Tree tree) {
    final Rectangle bounds = tree.getPathBounds(new TreePath(node.getPath()));
    if (bounds == null) {
      return null;
    }
    final Icon icon = ((ProjectStructureNode)node).getDescriptor().getIcon();
    int xAdjustment = 0;
    if (icon != null) {
      xAdjustment = icon.getIconWidth();
    }
    return new Point(bounds.x + xAdjustment, bounds.y + bounds.height);
  }

  /**
   * Tries to find the current {@link GradleProjectStructureTreeModel} instance.
   *
   * @param context  target context (if defined)
   * @return         current {@link GradleProjectStructureTreeModel} instance (if any has been found); <code>null</code> otherwise
   */
  @Nullable
  public static GradleProjectStructureTreeModel getProjectStructureTreeModel(@Nullable DataContext context) {
    return getToolWindowElement(GradleProjectStructureTreeModel.class, context, GradleDataKeys.SYNC_TREE_MODEL);
  }
  
  @Nullable
  public static <T> T getToolWindowElement(@NotNull Class<T> clazz, @Nullable DataContext context, @NotNull DataKey<T> key) {
    if (context != null) {
      final T result = key.getData(context);
      if (result != null) {
        return result;
      }
    }

    if (context == null) {
      return null;
    }

    final Project project = PlatformDataKeys.PROJECT.getData(context);
    if (project == null) {
      return null;
    }

    return getToolWindowElement(clazz, project, key);
  }

  @SuppressWarnings("unchecked")
  @Nullable
  public static <T> T getToolWindowElement(@NotNull Class<T> clazz, @NotNull Project project, @NotNull DataKey<T> key) {
    final ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
    if (toolWindowManager == null) {
      return null;
    }
    final ToolWindow toolWindow = toolWindowManager.getToolWindow(GradleConstants.TOOL_WINDOW_ID);
    if (toolWindow == null) {
      return null;
    }

    final ContentManager contentManager = toolWindow.getContentManager();
    if (contentManager == null) {
      return null;
    }

    for (Content content : contentManager.getContents()) {
      final JComponent component = content.getComponent();
      if (component instanceof DataProvider) {
        final Object data = ((DataProvider)component).getData(key.getName());
        if (data != null && clazz.isInstance(data)) {
          return (T)data;
        }
      }
    }
    return null;
  }
  
  /**
   * @return    {@link MatrixControlBuilder} with predefined set of columns ('gradle' and 'intellij')
   */
  @NotNull
  public static MatrixControlBuilder getConflictChangeBuilder() {
    final String gradle = ExternalSystemBundle.message("gradle.name");
    final String intellij = ExternalSystemBundle.message("gradle.ide");
    return new MatrixControlBuilder(gradle, intellij);
  }
  
  public static boolean isGradleAvailable(@Nullable Project project) {
    if (project != null) {
      GradleSettings settings = GradleSettings.getInstance(project);
      if (!settings.isPreferLocalInstallationToWrapper() && isGradleWrapperDefined(settings.getLinkedProjectPath())) {
        return true;
      }
    }
    return INSTALLATION_MANAGER.getValue().getGradleHome(project) != null;
  }

  @NotNull
  public static String getOutdatedEntityName(@NotNull String entityName, @NotNull String gradleVersion, @NotNull String ideVersion) {
    return String.format("%s (%s -> %s)", entityName, ideVersion, gradleVersion);
  }
  
  private interface TaskUnderProgress {
    void execute(@NotNull ProgressIndicator indicator);
  }
  
  /**
   * We use this class in order to avoid static initialisation of the wrapped object - it loads number of pico container-based
   * dependencies that are unavailable to the slave gradle project, so, we don't want to get unexpected NPE there.
   */
  private static class DescriptorHolder {
    public static final FileChooserDescriptor GRADLE_BUILD_FILE_CHOOSER_DESCRIPTOR = new OpenProjectFileChooserDescriptor(true) {
      @Override
      public boolean isFileSelectable(VirtualFile file) {
        return GradleConstants.DEFAULT_SCRIPT_NAME.equals(file.getName());
      }

      @Override
      public boolean isFileVisible(VirtualFile file, boolean showHiddenFiles) {
        if (!super.isFileVisible(file, showHiddenFiles)) {
          return false;
        }
        return file.isDirectory() || GradleConstants.DEFAULT_SCRIPT_NAME.equals(file.getName());
      }
    };

    public static final FileChooserDescriptor GRADLE_HOME_FILE_CHOOSER_DESCRIPTOR
      = new FileChooserDescriptor(false, true, false, false, false, false);
  }
}
