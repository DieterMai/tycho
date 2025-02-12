/*******************************************************************************
 * Copyright (c) 2023 Christoph Läubrich and others.
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Christoph Läubrich - initial API and implementation
 *******************************************************************************/
package org.eclipse.tycho.packaging;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.apache.maven.SessionScoped;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.Logger;
import org.eclipse.tycho.ClasspathEntry;
import org.eclipse.tycho.classpath.ClasspathContributor;
import org.eclipse.tycho.helper.PluginConfigurationHelper;
import org.eclipse.tycho.helper.PluginRealmHelper;
import org.osgi.framework.Constants;
import org.osgi.framework.namespace.ExecutionEnvironmentNamespace;
import org.osgi.resource.Capability;

import aQute.bnd.header.OSGiHeader;
import aQute.bnd.header.Parameters;
import aQute.bnd.osgi.Analyzer;
import aQute.bnd.osgi.Jar;
import aQute.bnd.osgi.resource.CapReqBuilder;

/**
 * Uses BND to compute a manifest and enhance the existing one with additional
 * headers, currently the following items are processed:
 * <ul>
 * <li><code>Require-Capability</code> is enhanced with additional items, if
 * <code>osgi.ee</code> capability is found, it replaces the deprecated
 * <code>Bundle-RequiredExecutionEnvironment</code></li>
 * </ul>
 */
@SessionScoped
@Component(role = ManifestProcessor.class, hint = "bnd", instantiationStrategy = "")
public class BndManifestProcessor implements ManifestProcessor {

	@Requirement
	private PluginRealmHelper pluginRealmHelper;
	@Requirement
	private Logger logger;

	@Requirement
	private PluginConfigurationHelper configurationHelper;

	private MavenSession mavenSession;

	@Inject
	public BndManifestProcessor(MavenSession mavenSession) {
		this.mavenSession = mavenSession;
	}

	@Override
	public void processManifest(MavenProject mavenProject, Manifest manifest) {

		if (configurationHelper.getBooleanOption("deriveHeaderFromSource")
				// don't be confused here, we use FALSE als default because it means no such
				// configuration option defined in the mojo (probably called from different
				// context) but the default in the PackagePluginMojo defines the real default
				// (what is TRUE)
				.orElse(Boolean.FALSE)) {
			File output = new File(mavenProject.getBuild().getOutputDirectory());
			if (!output.exists()) {
				return;
			}
			try (Jar jar = new Jar(mavenProject.getId(), output);
					Analyzer analyzer = new Analyzer(jar)) {
				for (Artifact artifact : mavenProject.getArtifacts()) {
					File file = artifact.getFile();
					if (file == null || !file.exists() || file.length() == 0) {
						// skip some bad items we never need to try out
						continue;
					}
					analyzer.addClasspath(file);
				}
				pluginRealmHelper.visitPluginExtensions(mavenProject, mavenSession, ClasspathContributor.class, cpc -> {
					List<ClasspathEntry> list = cpc.getAdditionalClasspathEntries(mavenProject, Artifact.SCOPE_COMPILE);
					if (list != null && !list.isEmpty()) {
						for (ClasspathEntry entry : list) {
							for (File file : entry.getLocations()) {
								try {
									analyzer.addClasspath(file);
								} catch (IOException e) {
								}
							}
						}
					}
				});
				Manifest calcManifest = analyzer.calcManifest();
				Attributes mainAttributes = manifest.getMainAttributes();
				Attributes calcAttributes = calcManifest.getMainAttributes();
				enhanceReqCap(mainAttributes, calcAttributes);
			} catch (Exception e) {
				String message = "Cannot derive header from source";
				if (logger.isDebugEnabled()) {
					logger.error(message, e);
				} else {
					logger.warn(message + " (" + e + ")");
				}
			}
		}
	}

	@SuppressWarnings("deprecation")
	private void enhanceReqCap(Attributes mainAttributes, Attributes calcAttributes) {
		String existingValue = mainAttributes.getValue(Constants.REQUIRE_CAPABILITY);
		String newValue = calcAttributes.getValue(Constants.REQUIRE_CAPABILITY);
		if (newValue != null) {
			Parameters additional = OSGiHeader.parseHeader(newValue);
			if (additional.containsKey(ExecutionEnvironmentNamespace.EXECUTION_ENVIRONMENT_NAMESPACE)) {
				// remove deprecated header but use the ee namespace
				mainAttributes.remove(new Attributes.Name(Constants.BUNDLE_REQUIREDEXECUTIONENVIRONMENT));
			}
			if (existingValue == null) {
				// easy case
				mainAttributes.putValue(Constants.REQUIRE_CAPABILITY, newValue);
			} else {
				// we need to merge them...
				logger.debug("Existing: " + existingValue);
				logger.debug("New:      " + newValue);
				Parameters current = OSGiHeader.parseHeader(existingValue);
				if (current.containsKey(ExecutionEnvironmentNamespace.EXECUTION_ENVIRONMENT_NAMESPACE)) {
					// if we have already an osgi.ee in the source just strip it from the new set
					additional.remove(ExecutionEnvironmentNamespace.EXECUTION_ENVIRONMENT_NAMESPACE);
				}
				List<Capability> initalCapabilities = CapReqBuilder.getCapabilitiesFrom(current);
				List<Capability> newCapabilities = CapReqBuilder.getCapabilitiesFrom(additional);
				if (newCapabilities.isEmpty()) {
					return;
				}
				Set<Capability> mergedCapabilities = new LinkedHashSet<>();
				mergedCapabilities.addAll(initalCapabilities);
				mergedCapabilities.addAll(newCapabilities);
				String merged = mergedCapabilities.stream().map(cap -> String.valueOf(cap).replace("'", "\""))
						.collect(Collectors.joining(","));
				logger.debug("Merged:   " + merged);
				mainAttributes.putValue(Constants.REQUIRE_CAPABILITY, merged);
			}
		}
	}

}
