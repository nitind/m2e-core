/*************************************************************************************
 * Copyright (c) 2012-2016 Red Hat, Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Red Hat, Inc - Initial implementation.
 ************************************************************************************/
package org.jboss.tools.maven.apt.tests;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.AssertionFailedException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.apt.core.internal.util.FactoryContainer;
import org.eclipse.jdt.apt.core.internal.util.FactoryPath;
import org.eclipse.jdt.apt.core.util.AptConfig;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.project.IProjectConfigurationManager;
import org.eclipse.m2e.core.project.ResolverConfiguration;
import org.eclipse.m2e.tests.common.AbstractMavenProjectTestCase;
import org.eclipse.osgi.util.NLS;
import org.jboss.tools.maven.apt.MavenJdtAptPlugin;
import org.jboss.tools.maven.apt.internal.Messages;
import org.jboss.tools.maven.apt.preferences.AnnotationProcessingMode;
import org.jboss.tools.maven.apt.preferences.IPreferencesManager;

@SuppressWarnings("restriction")
public class M2eAptProjectconfiguratorTest extends AbstractMavenProjectTestCase {

	private static final String COMPILER_OUTPUT_DIR = "target/generated-sources/annotations";
	private static final String PROCESSOR_OUTPUT_DIR = "target/generated-sources/apt";
	
	public void setUp() throws Exception {
		super.setUp();
		IPreferencesManager preferencesManager = MavenJdtAptPlugin.getDefault().getPreferencesManager();
		preferencesManager.setAnnotationProcessorMode(null, AnnotationProcessingMode.jdt_apt);
	}

	public void testMavenCompilerPluginSupport() throws Exception {
		defaultTest("p1", COMPILER_OUTPUT_DIR);
	}

	public void testMavenCompilerPluginDependencies() throws Exception {
		defaultTest("p2", "target/generated-sources/m2e-apt");
	}

	public void testMavenProcessorPluginSupport() throws Exception {
		defaultTest("p3", PROCESSOR_OUTPUT_DIR);
	}

	public void testDisabledAnnotationProcessing() throws Exception {
		testDisabledAnnotationProcessing("p4");//using <compilerArgument>-proc:none</compilerArgument>
		testDisabledAnnotationProcessing("p5");//using <proc>none</proc>
	}

	public void testAnnotationProcessorArguments() throws Exception {
		Map<String, String> expectedOptions = new HashMap<>(2);
		expectedOptions.put("addGenerationDate", "true");
		expectedOptions.put("addGeneratedAnnotation", "true");
		testAnnotationProcessorArguments("p6", expectedOptions);
		testAnnotationProcessorArguments("p7", expectedOptions);
	}

	public void testAnnotationProcessorArgumentsMap() throws Exception {
		Map<String, String> expectedOptions = new HashMap<>(2);
		expectedOptions.put("addGenerationDate", "true");
		// this option is false in <compilerArguments> but is overriden by <compilerArgument>
		expectedOptions.put("addGeneratedAnnotation", "true");
		expectedOptions.put("flag", null);
		IProject p = testAnnotationProcessorArguments("argumentMap", expectedOptions);
		List<IMarker> errors = findErrorMarkers(p);
		assertEquals(1, errors.size());
		String expectedMsg = NLS.bind(Messages.ProjectUtils_error_invalid_option_name, "-foo");
		assertEquals(expectedMsg, errors.get(0).getAttribute(IMarker.MESSAGE));
	}

	public void testNoAnnotationProcessor() throws Exception {
		IProject p = importProject("projects/p0/pom.xml");
		waitForJobsToComplete();

		// Import doesn't build, so we trigger it manually
		p.build(IncrementalProjectBuilder.FULL_BUILD, monitor);
		waitForJobsToComplete();

		IJavaProject javaProject = JavaCore.create(p);
		assertNotNull(javaProject);

		assertFalse("Annotation processing is enabled for "+p, AptConfig.isEnabled(javaProject));
        String expectedOutputFolder = COMPILER_OUTPUT_DIR;
		IFolder annotationsFolder = p.getFolder(expectedOutputFolder );
        assertFalse(annotationsFolder  + " was generated", annotationsFolder.exists());
	}


	public void testRuntimePluginDependency() throws Exception {

		IProject p = importProject("projects/eclipselink/pom.xml");
		waitForJobsToComplete();

		// Import doesn't build, so we trigger it manually
		p.build(IncrementalProjectBuilder.FULL_BUILD, monitor);
		waitForJobsToComplete();

		IJavaProject javaProject = JavaCore.create(p);
		assertNotNull(javaProject);

		assertTrue("Annotation processing is disabled for "+p, AptConfig.isEnabled(javaProject));
        String expectedOutputFolder = COMPILER_OUTPUT_DIR;
		IFolder annotationsFolder = p.getFolder(expectedOutputFolder );
        assertTrue(annotationsFolder  + " was not generated", annotationsFolder.exists());

        FactoryPath factoryPath = (FactoryPath) AptConfig.getFactoryPath(javaProject);
        String modelGen = "org.eclipse.persistence.jpa.modelgen.processor-2.5.1.jar";
        boolean foundRuntimeDependency = false;
        for (FactoryContainer container : factoryPath.getEnabledContainers().keySet()) {
			if (("M2_REPO/org/eclipse/persistence/org.eclipse.persistence.jpa.modelgen.processor/2.5.1/" + modelGen).equals(container.getId())){
				foundRuntimeDependency = true;
				break;
			}
		}
        assertTrue(modelGen + " was not found", foundRuntimeDependency);

        IFile generatedFile = p.getFile(expectedOutputFolder + "/foo/bar/Dummy_.java");
        if (!generatedFile.exists()) {
        	//APT was triggered during project configuration, i.e. before META-INF/persistence.xml was copied to
        	//target/classes by the maven-resource-plugin build participant. eclipselink modelgen could not find it
        	// and skipped model generation. Pretty annoying and I dunno how to fix that ... yet.

        	//Let's check a nudge to Dummy.java fixes this.
        	IFile dummy = p.getFile("src/main/java/foo/bar/Dummy.java");
        	dummy.touch(monitor);
        	p.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, monitor);
        	waitForJobsToComplete();
        }

        assertTrue(generatedFile + " was not generated", generatedFile.exists());
		assertNoErrors(p);
	}

	public void testDisableAnnotationProcessingFromWorkspace() throws Exception {
		IPreferencesManager preferencesManager = MavenJdtAptPlugin.getDefault().getPreferencesManager();
		try {
			preferencesManager.setAnnotationProcessorMode(null, AnnotationProcessingMode.disabled);
			IProject p = importProject("projects/p1/pom.xml");
			waitForJobsToComplete();
			IJavaProject javaProject = JavaCore.create(p);
			assertFalse("JDT APT support was enabled", AptConfig.isEnabled(javaProject));

			IFolder annotationsFolder = p.getFolder(COMPILER_OUTPUT_DIR);
		    assertFalse(annotationsFolder  + " was generated", annotationsFolder.exists());

		} finally {
			preferencesManager.setAnnotationProcessorMode(null, AnnotationProcessingMode.jdt_apt);
		}
	}

	public void testDisableAnnotationProcessingFromProject() throws Exception {
		IProject p = importProject("projects/p1/pom.xml");
		waitForJobsToComplete();
		IJavaProject javaProject = JavaCore.create(p);
		assertTrue("JDT APT support was not enabled", AptConfig.isEnabled(javaProject));

		//Manually disable APT support
		AptConfig.setEnabled(javaProject, false);

		//Disable m2e-apt on the project
		IPreferencesManager preferencesManager =MavenJdtAptPlugin.getDefault().getPreferencesManager();
		preferencesManager.setAnnotationProcessorMode(p, AnnotationProcessingMode.disabled);

		//Update Maven Configuration
		updateProject(p);

		//Check APT support is still disabled
		assertFalse("JDT APT support was enabled", AptConfig.isEnabled(javaProject));

	}

	public void testDisableProcessDuringReconcileFromWorkspace()
			throws Exception {
		IPreferencesManager preferencesManager = MavenJdtAptPlugin.getDefault()
				.getPreferencesManager();

		preferencesManager.setAnnotationProcessDuringReconcile(null, false);
		IProject p = importProject("projects/p1/pom.xml");
		waitForJobsToComplete();
		IJavaProject javaProject = JavaCore.create(p);
		assertFalse("JDT APT Processing on Edit was enabled",
				AptConfig.shouldProcessDuringReconcile(javaProject));

	}

	public void testDisableProcessDuringReconcileFromProject() throws Exception {
		IProject p = importProject("projects/p1/pom.xml");
		waitForJobsToComplete();
		IJavaProject javaProject = JavaCore.create(p);
		assertTrue("JDT APT Processing on Edit was not enabled",
				AptConfig.shouldProcessDuringReconcile(javaProject));

		IPreferencesManager preferencesManager = MavenJdtAptPlugin.getDefault()
				.getPreferencesManager();
		preferencesManager.setAnnotationProcessDuringReconcile(p, false);

		// Update Maven Configuration
		updateProject(p);

		// Check APT process on edit is still disabled
		assertFalse("JDT APT Processing on Edit was enabled",
				AptConfig.shouldProcessDuringReconcile(javaProject));
	}

	public void testMavenPropertyProcessDuringReconcileSupport()
			throws Exception {
		IPreferencesManager preferencesManager = MavenJdtAptPlugin.getDefault()
				.getPreferencesManager();
		preferencesManager.setAnnotationProcessDuringReconcile(null, true);
		IProject p = importProject("projects/p10/pom.xml");
		waitForJobsToComplete();
		IJavaProject javaProject = JavaCore.create(p);
		assertNotNull(javaProject);
		assertFalse(AptConfig.shouldProcessDuringReconcile(javaProject));

		preferencesManager.setAnnotationProcessDuringReconcile(p, true);
		updateProject(p);

		// Check Eclipse Project settings override pom property
		assertTrue("JDT APT Processing on Edit disabled for " + p,
				AptConfig.shouldProcessDuringReconcile(javaProject));
	}


	public void testPluginExecutionDelegation() throws Exception {
		IPreferencesManager preferencesManager = MavenJdtAptPlugin.getDefault().getPreferencesManager();
		try {
			preferencesManager.setAnnotationProcessorMode(null, AnnotationProcessingMode.maven_execution);
			IProject p = importProject("projects/p3/pom.xml");
			waitForJobsToComplete();

			p.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, monitor);
			waitForJobsToComplete();

			IJavaProject javaProject = JavaCore.create(p);
			assertFalse("JDT APT support was enabled", AptConfig.isEnabled(javaProject));

			IFolder annotationsFolder = p.getFolder(PROCESSOR_OUTPUT_DIR);
		    assertTrue(annotationsFolder  + " was not generated", annotationsFolder.exists());

			IFolder testAnnotationsFolder = p.getFolder("target/generated-sources/apt-test");
		    assertTrue(testAnnotationsFolder  + " was not generated", testAnnotationsFolder.exists());

		} finally {
			preferencesManager.setAnnotationProcessorMode(null, AnnotationProcessingMode.jdt_apt);
		}
	}


	private void testDisabledAnnotationProcessing(String projectName) throws Exception {
		IProject p = importProject("projects/"+projectName+"/pom.xml");
		waitForJobsToComplete();
		IJavaProject javaProject = JavaCore.create(p);
		assertNotNull(javaProject);
		assertFalse(AptConfig.isEnabled(javaProject));
	}

	private IProject testAnnotationProcessorArguments(String projectName, Map<String, String> expectedOptions) throws Exception {
		IProject p = importProject("projects/"+projectName+"/pom.xml");
		waitForJobsToComplete();
		IJavaProject javaProject = JavaCore.create(p);
		assertNotNull(javaProject);
		assertTrue("Annotation processing is disabled for "+projectName, AptConfig.isEnabled(javaProject));
		Map<String, String> options = AptConfig.getProcessorOptions(javaProject);
		for (Map.Entry<String, String> option : expectedOptions.entrySet()) {
			assertEquals(option.getValue(), options.get(option.getKey()));
			if (option.getValue() == null) {
				assertTrue(option.getKey() + " is missing ", options.containsKey(option.getKey()));
			}
		}
		return p;
	}

	private void defaultTest(String projectName, String expectedOutputFolder) throws Exception {

		IProject p = importProject("projects/"+projectName+"/pom.xml");
		waitForJobsToComplete();

		p.build(IncrementalProjectBuilder.FULL_BUILD, monitor);
		waitForJobsToComplete();

		IJavaProject javaProject = JavaCore.create(p);
		assertNotNull(javaProject);

		assertTrue("Annotation processing is disabled for "+p, AptConfig.isEnabled(javaProject));
        IFolder annotationsFolder = p.getFolder(expectedOutputFolder);
        assertTrue(annotationsFolder  + " was not generated", annotationsFolder.exists());

        FactoryPath factoryPath = (FactoryPath) AptConfig.getFactoryPath(javaProject);
        Iterator<FactoryContainer> ite = factoryPath.getEnabledContainers().keySet().iterator();
        FactoryContainer jpaModelGen = ite.next();
        assertEquals(FactoryContainer.FactoryType.VARJAR, jpaModelGen.getType());
        assertEquals("M2_REPO/org/hibernate/hibernate-jpamodelgen/1.1.1.Final/hibernate-jpamodelgen-1.1.1.Final.jar", jpaModelGen.getId());

        FactoryContainer jpaApi = ite.next();
        assertEquals(FactoryContainer.FactoryType.VARJAR, jpaApi.getType());
        assertEquals("M2_REPO/org/hibernate/javax/persistence/hibernate-jpa-2.0-api/1.0.0.Final/hibernate-jpa-2.0-api-1.0.0.Final.jar", jpaApi.getId());

        IFile generatedFile = p.getFile(expectedOutputFolder + "/foo/bar/Dummy_.java");
		assertTrue(generatedFile + " was not generated", generatedFile.exists());
		assertNoErrors(p);
	}

	 protected void updateProject(IProject project) throws Exception {
	    updateProject(project, null);
	  }

	 protected void updateProject(IProject project, String newPomName) throws Exception {

	    if (newPomName != null) {
	      copyContent(project, newPomName, "pom.xml");
	    }

	    IProjectConfigurationManager configurationManager = MavenPlugin.getProjectConfigurationManager();
	    ResolverConfiguration configuration = new ResolverConfiguration();
	    configurationManager.enableMavenNature(project, configuration, monitor);
	    configurationManager.updateProjectConfiguration(project, monitor);
	    waitForJobsToComplete();
	    project.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, monitor);
	    waitForJobsToComplete();
	  }


	public void testMavenPropertySupport1() throws Exception {
		IPreferencesManager preferencesManager = MavenJdtAptPlugin.getDefault().getPreferencesManager();
		preferencesManager.setAnnotationProcessorMode(null, AnnotationProcessingMode.disabled);
		//Check pom property overrides Workspace settings
		defaultTest("p8", PROCESSOR_OUTPUT_DIR);
	}

	public void testMavenPropertySupport2() throws Exception {
	    IPreferencesManager preferencesManager = MavenJdtAptPlugin.getDefault().getPreferencesManager();
	    preferencesManager.setAnnotationProcessorMode(null, AnnotationProcessingMode.jdt_apt);
	    IProject p = importProject("projects/p9/pom.xml");
	    waitForJobsToComplete();
	    IJavaProject javaProject = JavaCore.create(p);
	    assertNotNull(javaProject);
	    assertFalse(AptConfig.isEnabled(javaProject));

	    preferencesManager.setAnnotationProcessorMode(p, AnnotationProcessingMode.jdt_apt);
	    updateProject(p);

      //Check Eclipse Project settings override pom property
	    assertTrue("Annotation processing is disabled for "+p, AptConfig.isEnabled(javaProject));
	    IFolder annotationsFolder = p.getFolder(PROCESSOR_OUTPUT_DIR);
	    assertTrue(annotationsFolder  + " was not generated", annotationsFolder.exists());
	}

	public void testCompilerArgs() throws Exception {
	    Map<String, String> expectedOptions = new HashMap<>(3);
      // this option is false in <compilerArguments>, overriden by <compilerArgument> and <compilerArgs>
	    expectedOptions.put("addGenerationDate", "true");
	    expectedOptions.put("addGeneratedAnnotation", "true");
	    expectedOptions.put("compilerArg", null);
	    expectedOptions.put("foo", "bar");
	    testAnnotationProcessorArguments("compilerArgs", expectedOptions);
	}

	public void testAnnotationProcessorsPaths() throws Exception {
		IProject p = importProject("projects/p11/pom.xml");
		waitForJobsToComplete();

		p.build(IncrementalProjectBuilder.FULL_BUILD, monitor);
		waitForJobsToComplete();

		IJavaProject javaProject = JavaCore.create(p);
		assertNotNull(javaProject);

		assertTrue("Annotation processing is disabled for "+p, AptConfig.isEnabled(javaProject));
		IFile generatedFile = p.getFile("target/generated-sources/annotations/foo/bar/Dummy_.java");
		assertTrue(generatedFile + " was not generated", generatedFile.exists());
		assertNoErrors(p);

		FactoryPath factoryPath = (FactoryPath) AptConfig.getFactoryPath(javaProject);
		Iterator<FactoryContainer> ite = factoryPath.getEnabledContainers().keySet().iterator();
		assertEquals (2, factoryPath.getEnabledContainers().size());

		assertEquals("M2_REPO/org/hibernate/hibernate-jpamodelgen/5.0.7.Final/hibernate-jpamodelgen-5.0.7.Final.jar", ite.next().getId());
		assertEquals("M2_REPO/org/jboss/logging/jboss-logging/3.3.0.Final/jboss-logging-3.3.0.Final.jar", ite.next().getId());
	}
	
	public void testDeleteStaleClasspathEntries() throws Exception {
		String expectedOutputFolder = PROCESSOR_OUTPUT_DIR;
		IProject p = importProject("projects/p12/pom.xml");
		p.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, monitor);

		waitForJobsToComplete();

		IFile generatedFile = p.getFile(expectedOutputFolder + "/foo/bar/Dummy_.java");
		assertTrue(generatedFile + " was not generated", generatedFile.exists());
		
		IJavaProject javaProject = JavaCore.create(p);
		assertNotNull(javaProject);
		assertClasspathEntry(javaProject, PROCESSOR_OUTPUT_DIR, true);
		assertClasspathEntry(javaProject, COMPILER_OUTPUT_DIR, false);
		
		updateProject(p, "new-pom.xml");

		p.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, monitor);
		waitForJobsToComplete();

		assertNoErrors(p);

		expectedOutputFolder = COMPILER_OUTPUT_DIR;
		generatedFile = p.getFile(expectedOutputFolder + "/foo/bar/Dummy_.java");
		assertTrue(generatedFile + " was not generated", generatedFile.exists());
		
		javaProject = JavaCore.create(p);
		assertClasspathEntry(javaProject, PROCESSOR_OUTPUT_DIR, false);
		assertClasspathEntry(javaProject, COMPILER_OUTPUT_DIR, true);
	}

	public void testNonJarDependency() throws Exception {
		IProject p = importProject("projects/nonjar_plugin_deps/pom.xml");
		waitForJobsToComplete();

		p.build(IncrementalProjectBuilder.FULL_BUILD, monitor);
		waitForJobsToComplete();

		IJavaProject javaProject = JavaCore.create(p);
		assertNotNull(javaProject);

		FactoryPath factoryPath = (FactoryPath) AptConfig.getFactoryPath(javaProject);
		Set<FactoryContainer> jars = factoryPath.getEnabledContainers().keySet();
		assertTrue(jars.size() > 2);
		boolean hasJPAModelGen = false;
		boolean hasHibernateJPA = false;
		boolean hasMavenPluginAPI = false;
		for (FactoryContainer j : jars) {
			assertEquals(FactoryContainer.FactoryType.VARJAR, j.getType());
			switch (j.getId()) {
			case "M2_REPO/org/hibernate/hibernate-jpamodelgen/1.1.1.Final/hibernate-jpamodelgen-1.1.1.Final.jar":
				hasJPAModelGen = true;
				break;
			case "M2_REPO/org/apache/maven/maven-plugin-api/2.0.9/maven-plugin-api-2.0.9.jar":
				hasMavenPluginAPI = true;
				break;
			case "M2_REPO/org/hibernate/javax/persistence/hibernate-jpa-2.0-api/1.0.0.Final/hibernate-jpa-2.0-api-1.0.0.Final.jar":
				hasHibernateJPA = true;
				break;
			default:
				assertTrue(j.getId().endsWith(".jar"));
			}
		}
		assertTrue("hibernate-jpamodelgen-1.1.1.Final.jar was not found in the factory path", hasJPAModelGen);
		assertTrue("maven-plugin-api-2.0.9.jar was not found in the factory path", hasMavenPluginAPI);
		assertTrue("hibernate-jpa-2.0-api-1.0.0.Final.jar was not found in the factory path", hasHibernateJPA);

		IFile generatedFile = p.getFile(COMPILER_OUTPUT_DIR + "/foo/bar/Dummy_.java");
		assertTrue(generatedFile + " was not generated", generatedFile.exists());
		assertNoErrors(p);
	}

	private void assertClasspathEntry(IJavaProject jp, String path, boolean present) throws Exception {
		IPath expectedPath = new Path(path);
		for (IClasspathEntry cpe : jp.getRawClasspath()) {
			if (expectedPath.equals(cpe.getPath())) {
				if (present) {
					return;
				} 
				throw new AssertionFailedException("Unexpected "+path+ " was found in the Classpath");
			}
		}
	}
}
