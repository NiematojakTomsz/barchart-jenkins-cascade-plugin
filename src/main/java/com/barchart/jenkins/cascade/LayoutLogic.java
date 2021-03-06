/**
 * Copyright (C) 2013 Barchart, Inc. <http://www.barchart.com/>
 *
 * All rights reserved. Licensed under the OSI BSD License.
 *
 * http://www.opensource.org/licenses/bsd-license.php
 */
package com.barchart.jenkins.cascade;

import static com.barchart.jenkins.cascade.MavenTokenMacro.*;
import static com.barchart.jenkins.cascade.PluginUtilities.*;
import hudson.FilePath;
import hudson.Util;
import hudson.XmlFile;
import hudson.maven.ModuleName;
import hudson.maven.MavenModule;
import hudson.maven.MavenModuleSet;
import hudson.maven.MavenModuleSetBuild;
import hudson.model.Action;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Items;
import hudson.model.TopLevelItem;
import hudson.model.Cause;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.ListView;
import hudson.plugins.git.GitSCM;
import hudson.scm.SCM;
import hudson.scm.SubversionSCM;
import hudson.tasks.BuildWrapper;
import hudson.util.DescribableList;
import hudson.util.VariableResolver;
import hudson.views.ListViewColumn;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jenkins.model.Jenkins;
import jenkins.scm.SCMCheckoutStrategy;

import org.apache.maven.model.Model;
import org.jenkinsci.plugins.tokenmacro.TokenMacro;
import org.joda.time.DateTime;
import org.jvnet.hudson.plugins.m2release.LastReleaseListViewColumn;

import com.barchart.jenkins.cascade.PluginUtilities.JenkinsTask;

/**
 * Layout build logic.
 * 
 * @author Andrei Pozolotin
 */
public class LayoutLogic implements PluginConstants {

	/**
	 * Generate cascade project name.
	 */
	public static String cascadeName(
			final BuildContext<MavenModuleSetBuild> context,
			final MavenModuleSet layoutProject) throws IOException {

		final LayoutBuildWrapper wrapper = layoutProject.getBuildWrappersList()
				.get(LayoutBuildWrapper.class);

		final String cascadePattern = wrapper.getLayoutOptions()
				.getCascadeProjectName();

		try {

			final String cascadeName = TokenMacro.expandAll(context.build(),
					context.listener(), cascadePattern);

			return cascadeName;

		} catch (final Exception e) {
			throw new IOException(e);
		}

	}

	/**
	 * Verify plug-in maven module nesting convention:
	 * <p>
	 * 1) Layout project must have modules.
	 * <p>
	 * 2) Do not permit modules for member projects.
	 */
	public static boolean checkModuleNesting(
			final BuildContext<MavenModuleSetBuild> context,
			final MavenModuleSet layoutProject) throws IOException {

		final Model layoutModel = mavenModel(layoutProject);

		if (layoutModel.getModules().isEmpty()) {
			context.logErr("Layout project has no modules: " + layoutModel);
			context.logErr("Cascade member projects must be defined in layout project as <module/> entries.");
			return false;
		}

		/** Layout project workspace. */
		final FilePath workspace = context.build().getWorkspace();

		final MavenModule layoutModule = layoutProject.getRootModule();

		/** Topologically sorted list of modules. */
		final List<MavenModule> moduleList = layoutProject
				.getDisabledModules(false);

		for (final MavenModule module : moduleList) {
			if (isSameModuleName(layoutModule, module)) {
				/** Layout project module */
				continue;
			} else {

				/** Relative path of this project in SCM repository. */
				final String modulePath = module.getRelativePath();

				final FilePath moduleFolder = workspace.child(modulePath);

				final FilePath pomFile = moduleFolder.child("pom.xml");

				final Model moduleModel = mavenModel(pomFile);

				if (moduleModel.getModules().isEmpty()) {
					continue;
				}

				context.logErr("Project contains <module/>: " + moduleModel);
				context.logErr("Cascade member projects must not be using  <module/> entries.");

				return false;
			}
		}

		return true;

	}

	/**
	 * Copy configuration from layout into member via XML, run post load hook.
	 */
	public static void cloneConfig(final MavenModuleSet layoutProject,
			final MavenModuleSet memberProject) throws IOException {

		/** Original parent. */
		final ItemGroup<? extends Item> memberParent = memberProject
				.getParent();

		/** Original name. */
		final String memberName = memberProject.getName();

		final XmlFile layoutConfig = Items.getConfigFile(layoutProject);

		final XmlFile memberConfig = Items.getConfigFile(memberProject);

		Util.copyFile(layoutConfig.getFile(), memberConfig.getFile());

		/** Reload project form XML. */
		memberConfig.unmarshal(memberProject);

		/** Invoke post load hook. */
		memberProject.onLoad(memberParent, memberName);

	}

	/**
	 * Create view if missing and add project to the view.
	 */
	public static void ensureProjectView(
			final BuildContext<MavenModuleSetBuild> context,
			final TopLevelItem project) throws IOException {

		final String viewName = context.layoutOptions().getLayoutViewName();

		final ListView view = ensureListView(viewName);

		view.add(project);

		ensureProjectViewColumns(view);

		context.logTab("Project view: " + view.getAbsoluteUrl());

	}

	/**
	 * Activate additional columns for the cascade view.
	 */
	public static void ensureProjectViewColumns(final ListView view)
			throws IOException {

		final DescribableList<ListViewColumn, Descriptor<ListViewColumn>> columnList = view
				.getColumns();

		final GraphViewColumn graphColumn = columnList
				.get(GraphViewColumn.class);

		if (graphColumn == null) {
			columnList.add(new GraphViewColumn());
		}

		final LastReleaseListViewColumn releaseColumn = columnList
				.get(LastReleaseListViewColumn.class);

		if (releaseColumn == null) {
			columnList.add(new LastReleaseListViewColumn());
		}

	}

	/**
	 * Update maven and jenkins metadata.
	 */
	public static List<Action> mavenValidateGoals(
			final BuildContext<MavenModuleSetBuild> context,
			final String... options) {
		final LayoutOptions layoutOptions = new LayoutOptions();
		final MavenGoalsIntercept goals = new MavenGoalsIntercept();
		goals.append(layoutOptions.getMavenValidateGoals());
		goals.append(options);
		final List<Action> list = new ArrayList<Action>();
		list.add(new DoLayoutBadge());
		list.add(new DoValidateBadge());
		list.add(goals);
		return list;
	}

	/**
	 * Generate member project name.
	 */
	public static String memberName(
			final BuildContext<MavenModuleSetBuild> context,
			final MavenModuleSet layoutProject, final MavenModule module)
			throws IOException {

		final ModuleName moduleName = module.getModuleName();

		final Map<String, String> moduleTokens = new HashMap<String, String>();
		moduleTokens.put(TOKEN_PROJECT_ID, moduleName.toString());
		moduleTokens.put(TOKEN_GROUP_ID, moduleName.groupId);
		moduleTokens.put(TOKEN_ARTIFACT_ID, moduleName.artifactId);

		final VariableResolver<String> moduleResolver = new VariableResolver.ByMap<String>(
				moduleTokens);

		final VariableResolver<String> buildResolver = context.build()
				.getBuildVariableResolver();

		@SuppressWarnings("unchecked")
		final VariableResolver<String> resolver = new VariableResolver.Union<String>(
				moduleResolver, buildResolver);

		final String memberPattern = context.layoutOptions()
				.getMemberProjectName();

		final String memberName = Util.replaceMacro(memberPattern, resolver);

		return memberName;

	}

	/**
	 * Layout build entry point.
	 */
	public static boolean process(
			final BuildContext<MavenModuleSetBuild> context) throws IOException {

		final MavenModuleSet layoutProject = mavenProject(context.build());

		final LayoutArgumentsAction action = context.build().getAction(
				LayoutArgumentsAction.class);

		final ProjectIdentity layoutIdentity = ProjectIdentity
				.ensureLayoutIdentity(layoutProject);

		final String layoutName = layoutProject.getName();

		context.log("");
		context.log("Layout action: " + action);
		context.log("Layout project: " + layoutName);
		context.log("Project identity: " + layoutIdentity);

		if (!checkModuleNesting(context, layoutProject)) {
			return false;
		}

		ensureProjectView(context, layoutProject);

		processLayout(context, layoutProject);

		processCascade(context, layoutProject, action);

		processMemberList(context, layoutProject, action);

		return true;
	}

	/**
	 * Process cascade project create/update/delete.
	 */
	public static void processCascade( //
			final BuildContext<MavenModuleSetBuild> context, //
			final MavenModuleSet layoutProject, //
			final LayoutArgumentsAction action //
	) throws IOException {

		final Jenkins jenkins = Jenkins.getInstance();

		final String layoutName = layoutProject.getName();
		final String cascadeName = cascadeName(context, layoutProject);

		context.log("");
		context.log("Layout project: " + layoutName);
		context.log("Cascade project: " + cascadeName);

		/**
		 * Create using name as distinction.
		 */
		final JenkinsTask projectCreate = new JenkinsTask() {
			public void run() throws IOException {
				if (isProjectExists(cascadeName)) {

					context.logErr("Cascade project exist, skip create.");

				} else {

					context.logTab("Creating cascade project.");

					final CascadeProject cascadeProject = jenkins
							.createProject(CascadeProject.class, cascadeName);

					final ProjectIdentity cascadeIdentity = ProjectIdentity
							.ensureCascadeIdentity(layoutProject,
									cascadeProject);

					context.logTab("Project identity: " + cascadeIdentity);

					context.logTab("Provide description.");
					{
						final StringBuilder text = new StringBuilder();
						text.append("Generated on:");
						text.append("<br>\n");
						text.append("<b>");
						text.append(new DateTime());
						text.append("</b>");
						text.append("<p>\n");
						cascadeProject.setDescription(text.toString());
					}

					context.logTab("Persist project.");
					{
						cascadeProject.save();
					}

					ensureProjectView(context, cascadeProject);

					context.logTab("Project created.");

				}
			}
		};

		/**
		 * Delete using identity as distinction.
		 */
		final JenkinsTask projectDelete = new JenkinsTask() {
			public void run() throws IOException {

				final CascadeProject cascadeProject = ProjectIdentity.identity(
						layoutProject).cascadeProject();

				if (cascadeProject == null) {

					context.logErr("Cascade project missing, skip delete.");

				} else {

					context.logTab("Project identity: "
							+ ProjectIdentity.identity(cascadeProject));

					context.logTab("Deleting cascade project.");

					try {
						cascadeProject.delete();
					} catch (final InterruptedException e) {
						e.printStackTrace();
					}

					context.logTab("Project deleted.");

				}
			}
		};

		/**
		 * Update using identity as distinction.
		 */
		final JenkinsTask projectUpdate = new JenkinsTask() {
			public void run() throws IOException {

				final CascadeProject cascadeProject = ProjectIdentity.identity(
						layoutProject).cascadeProject();

				if (cascadeProject == null) {

					context.logErr("Cascade project missing, skip update.");

				} else {

					context.logTab("Project identity: "
							+ ProjectIdentity.identity(cascadeProject));

					context.logTab("Updating cascade project.");

					context.logTab("Persist project.");
					{
						cascadeProject.save();
					}

					ensureProjectView(context, cascadeProject);

					context.logTab("Project updated.");

				}

			}
		};

		switch (action.getConfigAction()) {
		default:
			context.logErr("Unexpected config action, ignore: "
					+ action.getConfigAction());
			break;
		case CREATE:
			projectCreate.run();
			break;
		case DELETE:
			projectDelete.run();
			break;
		case UPDATE:
			final CascadeProject cascadeProject = ProjectIdentity.identity(
					layoutProject).cascadeProject();
			if (cascadeProject == null) {
				context.logTab("Project missing, creating now.");
				projectCreate.run();
			} else {
				context.logTab("Project present, updating now.");
				projectUpdate.run();
			}
			break;
		}

	}

	/**
	 * Process layout project settings.
	 * 
	 * @throws IOException
	 */
	public static void processLayout(
			final BuildContext<MavenModuleSetBuild> context,
			final MavenModuleSet layoutProject) throws IOException {

		context.logTab("Update SCM settings.");
		SCM: {

			final SCM scm = layoutProject.getScm();

			if (scm instanceof GitSCM) {

				final GitSCM gitScm = (GitSCM) scm;

				final String includedRegions = "disabled-by" + "_" + PLUGIN_ID;
				try {
					changeField(gitScm, "includedRegions", includedRegions);
				} catch (IOException e) {
					// TODO: I'm not sure what we want to do with this field.
					context.logTab("Assuming repository pooling trigger is disabled.");
				}

				break SCM;

			}

			if (scm instanceof SubversionSCM) {

				final SubversionSCM svnScm = (SubversionSCM) scm;

				/** TODO */

			}

			throw new IllegalStateException("Unsupported SCM");

		}

		context.logTab("Use custom checkout strategy.");
		{
			final SCMCheckoutStrategy strategy = new CheckoutStrategySCM();
			layoutProject.setScmCheckoutStrategy(strategy);
		}

	}

	/**
	 * Process member project list create/update/delete.
	 */
	public static boolean processMemberList(//
			final BuildContext<MavenModuleSetBuild> context,//
			final MavenModuleSet layoutProject,//
			final LayoutArgumentsAction action //
	) throws IOException {

		switch (action.getConfigAction()) {
		default:
			context.logErr("Unexpected config action, ignore: "
					+ action.getConfigAction());
			break;
		case CREATE:
			processMemberListCreate(context, layoutProject);
			break;
		case DELETE:
			processMemberListDelete(context, layoutProject);
			break;
		case UPDATE:
			processMemberListUpdate(context, layoutProject);
			break;
		}

		return true;

	}

	/**
	 * Create member list using name as distinction.
	 */
	public static boolean processMemberListCreate(//
			final BuildContext<MavenModuleSetBuild> context,//
			final MavenModuleSet layoutProject //
	) throws IOException {

		final Jenkins jenkins = Jenkins.getInstance();

		final List<MavenModule> moduleList = layoutProject
				.getDisabledModules(false);

		for (final MavenModule module : moduleList) {

			final ModuleName moduleName = module.getModuleName();

			/**
			 * Module-to-Project naming convention.
			 */
			final String memberName = memberName(context, layoutProject, module);

			context.log("");
			context.log("Module name: " + moduleName);
			context.log("Member project: " + memberName);

			if (isSameModuleName(layoutProject.getRootModule(), module)) {
				context.logTab("This is a layout module project, managed by user, skip.");
				continue;
			}

			if (isProjectExists(memberName)) {

				context.logErr("Project exists, create skipped: " + memberName);

			} else {

				context.logTab("Creating project: " + memberName);

				/** Clone project via XML. */
				final TopLevelItem item = jenkins.copy(
						(TopLevelItem) layoutProject, memberName);

				final MavenModuleSet memberProject = (MavenModuleSet) item;

				processMemberUpdate(context, module, memberProject,
						layoutProject);

				processMemberValidate(context, memberProject);

				ensureProjectView(context, memberProject);

				context.logTab("Project created: " + memberName);

			}

		}

		return true;
	}

	/**
	 * Delete member list using identity as distinction.
	 */
	public static boolean processMemberListDelete(//
			final BuildContext<MavenModuleSetBuild> context,//
			final MavenModuleSet layoutProject //
	) throws IOException {

		final String familyID = ProjectIdentity.familyID(layoutProject);

		final List<MavenModuleSet> memberProjectList = ProjectIdentity
				.memberProjectList(familyID);

		if (memberProjectList.isEmpty()) {
			context.logErr("No member projects in the family: " + familyID);
			return false;
		}

		for (final MavenModuleSet memberProject : memberProjectList) {

			context.log("");
			context.log("Member project: " + memberProject.getName());

			context.logTab("Project identity: "
					+ ProjectIdentity.identity(memberProject));

			context.logTab("Deleting project.");

			try {
				memberProject.delete();
				context.logTab("Project deleted.");
			} catch (final Exception e) {
				context.logExc(e);
				context.logErr("Failed to delete project.");
			}

		}

		return true;

	}

	/**
	 * Update member list using identity as distinction.
	 */
	public static boolean processMemberListUpdate( //
			final BuildContext<MavenModuleSetBuild> context, //
			final MavenModuleSet layoutProject //
	) throws IOException {

		final String familyID = ProjectIdentity.familyID(layoutProject);

		final List<MavenModuleSet> memberProjectList = ProjectIdentity
				.memberProjectList(familyID);

		if (memberProjectList.isEmpty()) {
			context.logErr("No member projects in the family: " + familyID);
			return false;
		}

		for (final MavenModuleSet memberProject : memberProjectList) {

			context.log("");
			context.log("Member project: " + memberProject.getName());

			context.logTab("Project identity: "
					+ ProjectIdentity.identity(memberProject));

			context.logTab("Updating project.");

			final ModuleName memberName = moduleName(memberProject);

			final MavenModule memberModule = layoutProject.getItem(memberName
					.toString());

			if (memberModule == null) {
				context.logErr("Missing layout module, skip update: "
						+ memberName);
				continue;
			}

			processMemberUpdate(context, memberModule, memberProject,
					layoutProject);

			processMemberValidate(context, memberProject);

			ensureProjectView(context, memberProject);

			context.logTab("Project updated: " + memberName);

		}

		return true;

	}

	/**
	 * Update configuration of existing member project based on the layout
	 * project, with member specifics.
	 */
	public static void processMemberUpdate(//
			final BuildContext<MavenModuleSetBuild> context,//
			final MavenModule memberModule, //
			final MavenModuleSet memberProject, //
			final MavenModuleSet layoutProject//
	) throws IOException {

		context.logTab("Clone config from layout into member.");
		cloneConfig(layoutProject, memberProject);

		context.logTab("Remove layout identity from the member.");
		memberProject.removeProperty(ProjectIdentity.class);

		context.logTab("Update member SCM settings.");
		SCM: {

			final SCM scm = memberProject.getScm();

			if (scm instanceof GitSCM) {

				final GitSCM gitScm = (GitSCM) scm;

				final String includedRegions = memberModule.getRelativePath()
						+ "/.*";

				try {
					changeField(gitScm, "includedRegions", includedRegions);

				} catch (IOException e) {
					// TODO: I don't know how to do it.
					context.logErr("You need to configure includedRegions/repository pooling manually.");
				}

				break SCM;

			}

			if (scm instanceof SubversionSCM) {

				final SubversionSCM svnScm = (SubversionSCM) scm;

				/** TODO */

			}

			throw new IllegalStateException("Unsupported SCM");

		}

		context.logTab("Update member maven setting.");
		{
			/** Member project is nested in the layout project. */
			final String rootPOM = memberModule.getRelativePath() + "/pom.xml";
			memberProject.setRootPOM(rootPOM);

			if (context.layoutOptions().getUseSharedWorkspace()) {

				final FilePath nodeRoot = Computer.currentComputer().getNode()
						.getRootPath();

				final FilePath layoutWorkspace = context.build().getWorkspace();

				final String memberWorkspace = relativePath(
						nodeRoot.getRemote(), layoutWorkspace.getRemote());

				memberProject.setCustomWorkspace(memberWorkspace);

				context.logTab("Member is sharing workspace with layout.");

			} else {

				context.logTab("Member is using its own private workspace.");

			}
		}

		context.logTab("Configure member build wrappers.");
		{
			final DescribableList<BuildWrapper, Descriptor<BuildWrapper>> buildWrapperList = memberProject
					.getBuildWrappersList();

			buildWrapperList.remove(LayoutBuildWrapper.class);

			// BuildWrapper item = null;
			// buildWrapperList.add(item);
		}

		context.logTab("Ensure member project identity.");
		{
			final ProjectIdentity memberdentity = ProjectIdentity
					.ensureMemberIdentity(layoutProject, memberProject);

			context.logTab("Identity: " + memberdentity);
		}

		context.logTab("Provide member project description.");
		{
			final StringBuilder text = new StringBuilder();
			text.append("Generated on:");
			text.append("<br>\n");
			text.append("<b>");
			text.append(new DateTime());
			text.append("</b>");
			text.append("<p>\n");
			memberProject.setDescription(text.toString());
		}

		context.logTab("Use custom checkout strategy.");
		{
			final SCMCheckoutStrategy strategy = new CheckoutStrategySCM();
			memberProject.setScmCheckoutStrategy(strategy);
		}

		context.logTab("Persist project changes.");
		{
			memberProject.save();
		}

	}

	/**
	 * Validate newly created member projects.
	 * <p>
	 * Build maven module, do not wait for completion.
	 */
	public static void processMemberValidate( //
			final BuildContext<MavenModuleSetBuild> context, //
			final MavenModuleSet project //
	) {

		context.logTab("Project: " + project.getAbsoluteUrl());

		final LayoutOptions options = context.layoutOptions();

		if (options.getBuildAfterLayout()) {

			final Cause cause = context.build().getCauses().get(0);

			final List<Action> actionList = mavenValidateGoals(context);

			if (options.getUseSharedWorkspace()) {
				actionList.add(new CheckoutSkipAction());
			}

			actionList.add(new LayoutLogicAction());

			project.scheduleBuild2(0, cause, actionList);

			context.logTab("Building now.");

		}

	}

	private LayoutLogic() {

	}

}
