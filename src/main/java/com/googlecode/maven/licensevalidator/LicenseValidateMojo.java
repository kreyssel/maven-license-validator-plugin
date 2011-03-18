/**
 * Copyright (C) 2011 tdarby <tim.darby.uk@googlemail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.googlecode.maven.licensevalidator;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.model.License;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;

/**
 * Maven goal for downloading the license files of all the current project's dependencies.
 *
 * @phase validate
 * @goal validate-licenses
 * @requiresDependencyResolution test
 *
 * @author tdarby
 */
public class LicenseValidateMojo extends AbstractMojo
{
    /**
     * The Maven Project Object
     *
     * @parameter expression="${project}"
     * @readonly
     */
    protected MavenProject project;

    /**
     * Used to build a maven projects from artifacts in the remote repository.
     *
     * @parameter expression="${component.org.apache.maven.project.MavenProjectBuilder}"
     * @readonly
     */
    protected MavenProjectBuilder projectBuilder;

    /**
     * Location of the local repository.
     *
     * @parameter expression="${localRepository}"
     * @readonly
     */
    protected ArtifactRepository localRepository;
    /**
     * List of Remote Repositories used by the resolver
     *
     * @parameter expression="${project.remoteArtifactRepositories}"
     * @readonly
     */
    protected List remoteRepositories;

    /**
     * Include transitive dependencies when downloading license files.
     *
     * @parameter default-value="true"
     * @since 2.0.0
     */
    private boolean includeTransitiveDependencies;

    /**
     * List of banned licenses - any entry can be a RegExp match string instead
     * of a specific license name.
     * Banned licenses are overriden by allowed licenses, so if a license of a
     * dependency matches both banned and allowed, then it is deemd to be allowed
     * 
     * @parameter
     */
    public List<String> bannedLicenses = Collections.emptyList();

    /**
     * List of banned licenses - any entry can be a RegExp match string instead
     * of a specific license name.
     * Banned licenses are overriden by allowed licenses, so if a license of a
     * dependency matches both banned and allowed, then it is deemd to be allowed
     *
     * @parameter
     */
    public List<String> allowedLicenses = Collections.emptyList();

    /**
     * List of artifacts which are allowed as long as they do not assert a license,
     * of the form groupId:artifactId, or a RegExp match string instead of a specific
     * dependency name (which will be matched against the artifacts by forming their
     * groupId and artifactId as above.
     *
     * Bypassed artifacts automatically pass the license check, as long as they
     * have no licenses specified - if they specify a license, they have to
     * meet the normal rules.
     *
     * @parameter
     */
    public List<String> allowedUnlicensed = Collections.emptyList();

    /**
     * Whether to treat unmatched license dependencies as allowed or banned - true for allowed.
     * Default is false
     *
     * @parameter default-value="false"
     */
    public boolean allowUnrecognised;

    /**
     * Whether to bomb out on the first license error or report all errors before
     * exiting
     *
     * @parameter default-value="true
     */
    public boolean failFast;

    /////////////////////////////////////////////////////////////////////////////
    /////////////////////////////////////////////////////////////////////////////
    @Override
    public void execute() throws MojoExecutionException
    {
        getLog().info("Validating licenses");

        
        final Set<Artifact> depArtifacts;
        if (includeTransitiveDependencies)
        {
            depArtifacts = project.getArtifacts();
        }
        else
        {
            depArtifacts = project.getDependencyArtifacts();
        }

        boolean someFailed = false;
        for (Artifact depArtifact : depArtifacts)
        {
            MavenProject depProject = null;
            try
            {
                depProject = projectBuilder.buildFromRepository(depArtifact, remoteRepositories, localRepository);
            }
            catch (ProjectBuildingException e)
            {
                throw new MojoExecutionException("Unable to build project: " + depArtifact.getDependencyConflictId(), e);
            }

            List<License> licenses = depProject.getLicenses();
            boolean failed = areLicensesBanned(depProject.getId(), licenses);
            someFailed |= failed;

            if (someFailed && failFast)
            {
                throw new MojoExecutionException("Failed license checks for dependency " + depProject.getId());
            }
        }

        if (someFailed)
        {
            throw new MojoExecutionException("Failed license checks for some dependencies");
        }
    }

    private boolean isUnlicensedBanned(String depProjectId)
    {
        if (allowedUnlicensed == null)
        {
            getLog().error("POM for dependency " + depProjectId + "has no license and will fail as we have no allowedUnlicensed matchers");
            return true;
        }
        else
        {
            getLog().info("POM for dependency " + depProjectId + "being checked against allowedUnlicensed[" + allowedUnlicensed + "]");
            for (String match : allowedUnlicensed)
            {
                if (depProjectId.equals(match) || depProjectId.matches(match))
                {
                    getLog().info("POM for dependency " + depProjectId + " matches allowed unlicensed matcher " + match);
                    return false;
                }
            }

            getLog().error("POM for dependency " + depProjectId + " does not contain license information and does not match any of the allowedUnlicensed matchers");
            return true;
        }
    }

    private boolean areLicensesBanned(String depProjectId, Collection<License> licenses)
    {
        if (licenses == null || licenses.isEmpty())
        {
            return isUnlicensedBanned(depProjectId);
        }
        
        boolean hasAllowed = false;
        boolean hasBanned = false;

        for (License license : licenses)
        {
            String name = license.getName();
            if (name == null)
            {
                return isUnlicensedBanned(depProjectId);
            }

            for (String allowedLicense : allowedLicenses)
            {
                if (name.equals(allowedLicense) || name.matches(allowedLicense))
                {
                    getLog().info("POM for dependency " + depProjectId + " matches allowed license " + name);
                    hasAllowed = true;
                }
            }

            for (String bannedLicense : bannedLicenses)
            {
                if (name.equals(bannedLicense) || name.matches(bannedLicense))
                {
                    getLog().warn("POM for dependency " + depProjectId + " matches banned license " + name + ".");
                    hasBanned = true;
                }
            }
        }
        
        if (hasAllowed)
        {
            getLog().info("POM for dependency " + depProjectId + " has at least one allowed license.");
            for (License license : licenses)
            {
                getLog().info(" - " + license.getName() + ".");
            }
            return false;
        }
        else if (hasBanned)
        {
            getLog().error("POM for dependency " + depProjectId + " has at least one banned license.");
            for (License license : licenses)
            {
                getLog().error(" - " + license.getName() + ".");
            }
            return true;
        }
        else
        {
            getLog().error("POM for dependency " + depProjectId + " had only licenses we are neither banning nor explicitly allowing.");
            for (License license : licenses)
            {
                getLog().error(" - " + license.getName() + ".");
            }
            return !(allowUnrecognised);
        }
    }
}
