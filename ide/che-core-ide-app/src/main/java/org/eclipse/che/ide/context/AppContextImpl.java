/*
 * Copyright (c) 2012-2017 Red Hat, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package org.eclipse.che.ide.context;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Lists.newArrayList;
import static java.util.Collections.addAll;
import static org.eclipse.che.api.workspace.shared.Constants.SERVER_WS_AGENT_HTTP_REFERENCE;
import static org.eclipse.che.ide.api.resources.ResourceDelta.ADDED;
import static org.eclipse.che.ide.api.resources.ResourceDelta.MOVED_FROM;
import static org.eclipse.che.ide.api.resources.ResourceDelta.MOVED_TO;
import static org.eclipse.che.ide.api.resources.ResourceDelta.REMOVED;
import static org.eclipse.che.ide.api.resources.ResourceDelta.UPDATED;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.google.web.bindery.event.shared.EventBus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.che.api.core.model.factory.Factory;
import org.eclipse.che.ide.api.app.AppContext;
import org.eclipse.che.ide.api.app.CurrentUser;
import org.eclipse.che.ide.api.app.StartUpAction;
import org.eclipse.che.ide.api.editor.EditorAgent;
import org.eclipse.che.ide.api.editor.EditorPartPresenter;
import org.eclipse.che.ide.api.factory.model.FactoryImpl;
import org.eclipse.che.ide.api.project.type.ProjectTypesLoadedEvent;
import org.eclipse.che.ide.api.resources.Container;
import org.eclipse.che.ide.api.resources.Project;
import org.eclipse.che.ide.api.resources.Resource;
import org.eclipse.che.ide.api.resources.ResourceChangedEvent;
import org.eclipse.che.ide.api.resources.ResourceChangedEvent.ResourceChangedHandler;
import org.eclipse.che.ide.api.resources.ResourceDelta;
import org.eclipse.che.ide.api.resources.ResourcePathComparator;
import org.eclipse.che.ide.api.resources.VirtualFile;
import org.eclipse.che.ide.api.selection.Selection;
import org.eclipse.che.ide.api.selection.SelectionChangedEvent;
import org.eclipse.che.ide.api.selection.SelectionChangedHandler;
import org.eclipse.che.ide.api.workspace.WorkspaceReadyEvent;
import org.eclipse.che.ide.api.workspace.event.WorkspaceStoppedEvent;
import org.eclipse.che.ide.api.workspace.model.MachineImpl;
import org.eclipse.che.ide.api.workspace.model.RuntimeImpl;
import org.eclipse.che.ide.api.workspace.model.ServerImpl;
import org.eclipse.che.ide.api.workspace.model.WorkspaceImpl;
import org.eclipse.che.ide.project.node.SyntheticNode;
import org.eclipse.che.ide.resource.Path;
import org.eclipse.che.ide.resources.impl.ResourceDeltaImpl;
import org.eclipse.che.ide.resources.impl.ResourceManager;
import org.eclipse.che.ide.statepersistance.AppStateManager;
import org.eclipse.che.ide.ui.smartTree.data.HasDataObject;
import org.eclipse.che.ide.util.loging.Log;

/**
 * Implementation of {@link AppContext}.
 *
 * @author Vitaly Parfonov
 * @author Artem Zatsarynnyi
 * @author Vlad Zhukovskyi
 */
@Singleton
public class AppContextImpl
    implements AppContext,
        SelectionChangedHandler,
        ResourceChangedHandler,
        WorkspaceStoppedEvent.Handler {

  private final List<String> projectsInImport;
  private final EventBus eventBus;
  private final ResourceManager.ResourceManagerFactory resourceManagerFactory;
  private final Provider<EditorAgent> editorAgentProvider;
  private final Provider<AppStateManager> appStateManager;

  private final List<Project> rootProjects = newArrayList();
  private final List<Resource> selectedResources = newArrayList();

  /**
   * List of actions with parameters which comes from startup URL. Can be processed after IDE
   * initialization as usual after starting ws-agent.
   */
  private final List<StartUpAction> startAppActions;

  private String applicationWebsocketId;
  private CurrentUser currentUser;
  private WorkspaceImpl workspace;
  private FactoryImpl factory;
  private Path projectsRoot;
  private ResourceManager resourceManager;
  private Map<String, String> properties;

  @Inject
  public AppContextImpl(
      EventBus eventBus,
      ResourceManager.ResourceManagerFactory resourceManagerFactory,
      Provider<EditorAgent> editorAgentProvider,
      Provider<AppStateManager> appStateManager) {
    this.eventBus = eventBus;
    this.resourceManagerFactory = resourceManagerFactory;
    this.editorAgentProvider = editorAgentProvider;
    this.appStateManager = appStateManager;
    this.startAppActions = new ArrayList<>();

    projectsInImport = new ArrayList<>();

    eventBus.addHandler(ProjectTypesLoadedEvent.TYPE, e -> initResourceManager());

    eventBus.addHandler(SelectionChangedEvent.TYPE, this);
    eventBus.addHandler(ResourceChangedEvent.getType(), this);
    eventBus.addHandler(WorkspaceStoppedEvent.TYPE, this);
  }

  private static native String getMasterApiPathFromIDEConfig() /*-{
        if ($wnd.IDE && $wnd.IDE.config) {
            return $wnd.IDE.config.restContext;
        } else {
            return null;
        }
    }-*/;

  @Override
  public WorkspaceImpl getWorkspace() {
    return workspace;
  }

  /** Sets the current workspace. */
  public void setWorkspace(WorkspaceImpl workspace) {
    this.workspace = new WorkspaceImpl(workspace);
  }

  @Override
  public String getWorkspaceId() {
    if (workspace == null) {
      throw new IllegalArgumentException(getClass() + " Workspace can not be null.");
    }

    return workspace.getId();
  }

  @Override
  public CurrentUser getCurrentUser() {
    if (currentUser == null) {
      throw new IllegalStateException(getClass() + " Current Workspace can not be null.");
    }
    return currentUser;
  }

  public void setCurrentUser(CurrentUser user) {
    this.currentUser = user;
  }

  @Override
  public List<String> getImportingProjects() {
    return projectsInImport;
  }

  @Override
  public void addProjectToImporting(String pathToProject) {
    projectsInImport.add(pathToProject);
  }

  @Override
  public void removeProjectFromImporting(String pathToProject) {
    projectsInImport.remove(pathToProject);
  }

  @Override
  public List<StartUpAction> getStartAppActions() {
    return startAppActions;
  }

  public void setStartAppActions(List<StartUpAction> startAppActions) {
    this.startAppActions.addAll(startAppActions);
  }

  @Override
  public FactoryImpl getFactory() {
    return factory;
  }

  public void setFactory(Factory factory) {
    this.factory = new FactoryImpl(factory);
  }

  private void initResourceManager() {
    if (!rootProjects.isEmpty()) {
      for (Project project : rootProjects) {
        eventBus.fireEvent(new ResourceChangedEvent(new ResourceDeltaImpl(project, REMOVED)));
      }
      rootProjects.clear();
    }

    resourceManager = resourceManagerFactory.newResourceManager();
    resourceManager
        .getWorkspaceProjects()
        .then(
            projects -> {
              rootProjects.clear();
              addAll(rootProjects, projects);
              rootProjects.sort(ResourcePathComparator.getInstance());
              eventBus.fireEvent(new WorkspaceReadyEvent(projects));
            })
        .catchError(
            error -> {
              Log.error(AppContextImpl.class, error.getCause());
            });
  }

  @Deprecated
  @Override
  public String getWorkspaceName() {
    return workspace.getConfig().getName();
  }

  /** {@inheritDoc} */
  @Override
  public void onResourceChanged(ResourceChangedEvent event) {
    final ResourceDelta delta = event.getDelta();
    final Resource resource = delta.getResource();

    if (delta.getKind() == ADDED) {
      if ((delta.getFlags() & (MOVED_FROM | MOVED_TO)) != 0) {

        for (Project rootProject : rootProjects) {
          if (rootProject.getLocation().equals(delta.getFromPath()) && resource.isProject()) {
            rootProjects.set(rootProjects.indexOf(rootProject), resource.asProject());
            break;
          }
        }

        for (Resource selectedResource : selectedResources) {
          if (selectedResource.getLocation().equals(delta.getFromPath())) {
            selectedResources.set(selectedResources.indexOf(selectedResource), resource);
            break;
          }
        }
      } else if (resource.getLocation().segmentCount() == 1 && resource.isProject()) {
        boolean exists =
            rootProjects.stream().anyMatch(it -> it.getLocation().equals(resource.getLocation()));

        if (!exists) {
          rootProjects.add(resource.asProject());
          rootProjects.sort(ResourcePathComparator.getInstance());
        }
      }
    } else if (delta.getKind() == REMOVED) {

      for (Project rootProject : rootProjects) {
        if (rootProject.getLocation().equals(resource.getLocation()) && resource.isProject()) {
          rootProjects.remove(rootProjects.indexOf(rootProject));
          break;
        }
      }

      for (Resource selectedResource : selectedResources) {
        if (selectedResource.getLocation().equals(resource.getLocation())) {
          selectedResources.remove(selectedResources.indexOf(selectedResource));
          break;
        }
      }
    } else if (delta.getKind() == UPDATED) {

      for (Project rootProject : rootProjects) {
        if (rootProject.getLocation().equals(resource.getLocation()) && resource.isProject()) {
          rootProjects.set(rootProjects.indexOf(rootProject), resource.asProject());
          break;
        }
      }

      for (Resource selectedResource : selectedResources) {
        if (selectedResource.getLocation().equals(resource.getLocation())) {
          selectedResources.set(selectedResources.indexOf(selectedResource), resource);
          break;
        }
      }
    }
  }

  @Override
  public Path getProjectsRoot() {
    return projectsRoot;
  }

  public void setProjectsRoot(Path projectsRoot) {
    this.projectsRoot = projectsRoot;
  }

  @Override
  public void onSelectionChanged(SelectionChangedEvent event) {
    final Selection<?> selection = event.getSelection();
    if (selection instanceof Selection.NoSelectionProvided) {
      return;
    }

    selectedResources.clear();

    if (selection != null) {
      for (Object o : selection.getAllElements()) {
        if (o instanceof HasDataObject && ((HasDataObject) o).getData() instanceof Resource) {
          selectedResources.add((Resource) ((HasDataObject) o).getData());
        } else if (o instanceof Resource) {
          selectedResources.add((Resource) o);
        }
      }
    }
  }

  @Override
  public Project[] getProjects() {
    return rootProjects.toArray(new Project[rootProjects.size()]);
  }

  @Override
  public Container getWorkspaceRoot() {
    checkState(resourceManager != null, "Workspace configuration has not been received yet");

    return resourceManager.getWorkspaceRoot();
  }

  @Override
  public Resource getResource() {
    return selectedResources.isEmpty() ? null : selectedResources.get(0);
  }

  @Override
  public Resource[] getResources() {
    return selectedResources.toArray(new Resource[selectedResources.size()]);
  }

  @Override
  public Project getRootProject() {
    if (rootProjects.isEmpty()) {
      return null;
    }

    if (selectedResources.isEmpty()) {
      EditorAgent editorAgent = editorAgentProvider.get();
      if (editorAgent == null) {
        return null;
      }

      final EditorPartPresenter editor = editorAgent.getActiveEditor();
      if (editor == null) {
        return null;
      }

      final VirtualFile file = editor.getEditorInput().getFile();

      if (file instanceof SyntheticNode) {
        final Path projectPath = ((SyntheticNode) file).getProject();
        for (Project project : rootProjects) {
          if (project.getLocation().equals(projectPath)) {
            return project;
          }
        }
      }

      return null;
    } else {
      Project root = null;

      for (Project project : rootProjects) {
        if (project.getLocation().isPrefixOf(selectedResources.get(0).getLocation())) {
          root = project;
          break;
        }
      }

      if (root == null) {
        return null;
      }

      for (int i = 1; i < selectedResources.size(); i++) {
        if (!root.getLocation().isPrefixOf(selectedResources.get(i).getLocation())) {
          return null;
        }
      }

      return root;
    }
  }

  @Override
  public void onWorkspaceStopped(WorkspaceStoppedEvent event) {
    appStateManager
        .get()
        .persistWorkspaceState()
        .then(
            ignored -> {
              for (Project project : rootProjects) {
                eventBus.fireEvent(
                    new ResourceChangedEvent(new ResourceDeltaImpl(project, REMOVED)));
              }

              rootProjects.clear();
              resourceManager = null;
            });
  }

  @Override
  public String getMasterApiEndpoint() {
    return getMasterApiPathFromIDEConfig();
  }

  @Override
  public String getWsAgentServerApiEndpoint() {
    RuntimeImpl runtime = getWorkspace().getRuntime();
    Optional<MachineImpl> devMachine = runtime.getDevMachine();

    if (devMachine.isPresent()) {
      Optional<ServerImpl> wsAgentServer =
          devMachine.get().getServerByName(SERVER_WS_AGENT_HTTP_REFERENCE);

      if (wsAgentServer.isPresent()) {
        return wsAgentServer.get().getUrl();
      }
    }

    throw new RuntimeException("ws-agent server doesn't exist");
  }

  @Override
  public Optional<String> getApplicationWebsocketId() {
    return Optional.ofNullable(applicationWebsocketId);
  }

  @Override
  public void setApplicationWebsocketId(String id) {
    this.applicationWebsocketId = id;
  }

  @Override
  public Map<String, String> getProperties() {
    if (properties == null) {
      properties = new HashMap<>();
    }
    return properties;
  }
}
