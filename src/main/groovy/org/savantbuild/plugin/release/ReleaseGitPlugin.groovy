/*
 * Copyright (c) 2014, Inversoft Inc., All Rights Reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package org.savantbuild.plugin.release

import org.savantbuild.domain.Project
import org.savantbuild.output.Output
import org.savantbuild.plugin.dep.DependencyPlugin
import org.savantbuild.plugin.groovy.BaseGroovyPlugin
import org.savantbuild.runtime.RuntimeConfiguration

import java.nio.file.Files
import java.nio.file.Path

/**
 * Release git plugin. This releases a project that is maintained in a Git repository.
 *
 * @author Brian Pontarelli
 */
class ReleaseGitPlugin extends BaseGroovyPlugin {

  ReleaseGitPlugin(Project project, RuntimeConfiguration runtimeConfiguration, Output output) {
    super(project, runtimeConfiguration, output)
    new DependencyPlugin(project, runtimeConfiguration, output)
  }

  void release() {
    // Check if this is a working copy
    Path gitDirectory = project.directory.resolve(".git")
    if (!Files.isDirectory(gitDirectory)) {
      fail("You can only run a release from a Git repository.")
    }

    if (!project.publishWorkflow) {
      fail("You must specify a publishWorkflow in the project definition of your build.savant script.")
    }

    Git git = new Git(project.directory)
    updateGitAndCheckWorkingCopy(git)
    checkIfTagIsAvailable(git)
    checkDependenciesForIntegrationVersions()
    checkPluginsForIntegrationVersions()
    tag(git)
    publish()
  }

  private void publish() {
    output.info("Publishing project artifacts")

    project.publications.allPublications().each({ publication ->
      project.dependencyService.publish(publication, project.publishWorkflow)
    })
  }

  private void tag(Git git) {
    output.info("Creating tag [${project.version}]")

    Process process = git.tag(project.version, "Release version [${project.version}].")
    if (process.exitValue() != 0) {
      fail("Unable to create Git tag for the release. Git output is:\n\n%s", process.text)
    }
  }

  private void checkPluginsForIntegrationVersions() {
    output.info("Checking plugins for integration versions")

    project.plugins.each({ dependency, plugin ->
      if (dependency.version.isIntegration()) {
        fail("Your project depends on the integration version of the plugin [${dependency}]. You cannot depend on " +
            "integration builds of plugins when releasing a project.")
      }
    })
  }

  private void checkDependenciesForIntegrationVersions() {
    output.info("Checking dependencies for integration versions")

    if (!project.artifactGraph) {
      return
    }

    project.artifactGraph.traverse(project.artifactGraph.root, true, { origin, destination, edge, depth ->
      if (destination.version.integration) {
        fail("Your project contains a dependency on the artifact [${destination}] which is an integration release. You " +
            "cannot depend on any integration releases when releasing a project.")
      }

      return true
    })
  }

  private void checkIfTagIsAvailable(Git git) {
    output.info("Checking if tag [${project.version}] already exists")

    Process process = git.fetchTags()
    if (process.exitValue() != 0) {
      fail("Unable to fetch new tags from the remote git repository. Unable to perform a release.")
    }

    if (git.doesTagExist(project.version)) {
      fail("It appears that the version [${project.version}] has already been released.")
    }
  }

  private void updateGitAndCheckWorkingCopy(Git git) {
    // Do a pull
    output.info("Updating working copy and verifying it can be released.")

    Process process = git.pull()
    if (process.exitValue() != 0) {
      fail("Unable to pull from remote Git repository. Unable to perform a release. Git output is:\n\n${process.text}")
    }

    // See if the working copy is ahead
    process = git.status("-sb")
    if (process.exitValue() != 0) {
      fail("Unable to check the status of your local git repository. Unable to perform a release. Git output is:\n\n${process.text}")
    }

    String status = process.text.trim()
    if (status.toLowerCase().contains("ahead")) {
      fail("Your git working copy appears to have local changes that haven't been pushed. Unable to perform a release.")
    }

    // Check for local modifications
    process = git.status("--porcelain")
    if (process.exitValue() != 0) {
      fail("Unable to check the status of your local git repository. Unable to perform a release. Git output is:\n\n${process.text}")
    }

    status = process.text.trim()
    if (!status.isEmpty()) {
      fail("Cannot release from a dirty directory. Git status output is:\n\n${status}")
    }
  }
}