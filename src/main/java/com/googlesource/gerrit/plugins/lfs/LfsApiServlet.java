// Copyright (C) 2016 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.lfs;

import static com.google.gerrit.extensions.api.lfs.LfsDefinitions.LFS_OBJECTS_PATH;
import static com.google.gerrit.extensions.api.lfs.LfsDefinitions.LFS_URL_REGEX_TEMPLATE;
import static com.google.gerrit.extensions.client.ProjectState.HIDDEN;
import static com.google.gerrit.extensions.client.ProjectState.READ_ONLY;
import static com.google.gerrit.server.permissions.ProjectPermission.ACCESS;
import static com.google.gerrit.server.permissions.ProjectPermission.PUSH_AT_LEAST_ONE_REF;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.ProjectUtil;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectState;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.lfs.auth.LfsAuthUserProvider;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.jgit.lfs.errors.LfsException;
import org.eclipse.jgit.lfs.errors.LfsRepositoryNotFound;
import org.eclipse.jgit.lfs.errors.LfsRepositoryReadOnly;
import org.eclipse.jgit.lfs.errors.LfsUnauthorized;
import org.eclipse.jgit.lfs.errors.LfsUnavailable;
import org.eclipse.jgit.lfs.errors.LfsValidationError;
import org.eclipse.jgit.lfs.server.LargeFileRepository;
import org.eclipse.jgit.lfs.server.LfsObject;
import org.eclipse.jgit.lfs.server.LfsProtocolServlet;

// Sampee
import com.googlesource.gerrit.plugins.lfs.ExtTransferHandler;
import com.googlesource.gerrit.plugins.lfs.ExtLogger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.http.HttpStatus.SC_FORBIDDEN;
import static org.apache.http.HttpStatus.SC_INSUFFICIENT_STORAGE;
import static org.apache.http.HttpStatus.SC_INTERNAL_SERVER_ERROR;
import static org.apache.http.HttpStatus.SC_NOT_FOUND;
import static org.apache.http.HttpStatus.SC_OK;
import static org.apache.http.HttpStatus.SC_SERVICE_UNAVAILABLE;
import static org.apache.http.HttpStatus.SC_UNAUTHORIZED;
import static org.apache.http.HttpStatus.SC_UNPROCESSABLE_ENTITY;
import static org.eclipse.jgit.lfs.lib.Constants.DOWNLOAD;
import static org.eclipse.jgit.lfs.lib.Constants.UPLOAD;
import static org.eclipse.jgit.lfs.lib.Constants.VERIFY;
import static org.eclipse.jgit.util.HttpSupport.HDR_AUTHORIZATION;

import org.eclipse.jgit.lfs.errors.LfsBandwidthLimitExceeded;
import org.eclipse.jgit.lfs.errors.LfsException;
import org.eclipse.jgit.lfs.errors.LfsInsufficientStorage;
import org.eclipse.jgit.lfs.errors.LfsRateLimitExceeded;
import org.eclipse.jgit.lfs.errors.LfsRepositoryNotFound;
import org.eclipse.jgit.lfs.errors.LfsRepositoryReadOnly;
import org.eclipse.jgit.lfs.errors.LfsUnauthorized;
import org.eclipse.jgit.lfs.errors.LfsUnavailable;
import org.eclipse.jgit.lfs.errors.LfsValidationError;
import org.eclipse.jgit.lfs.internal.LfsText;
import org.eclipse.jgit.lfs.server.internal.LfsGson;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.text.MessageFormat;
import java.util.List;
//
@Singleton
public class LfsApiServlet extends LfsProtocolServlet {
  public static final String LFS_OBJECTS_REGEX_REST =
      String.format(LFS_URL_REGEX_TEMPLATE, LFS_OBJECTS_PATH);

  private static final FluentLogger log = FluentLogger.forEnclosingClass();
  private static final long serialVersionUID = 1L;
  private static final Pattern URL_PATTERN = Pattern.compile(LFS_OBJECTS_REGEX_REST);

  private final ProjectCache projectCache;
  private final PermissionBackend permissionBackend;
  private final LfsConfigurationFactory lfsConfigFactory;
  private final LfsRepositoryResolver repoResolver;
  private final LfsAuthUserProvider userProvider;

  @Inject
  LfsApiServlet(
      ProjectCache projectCache,
      PermissionBackend permissionBackend,
      LfsConfigurationFactory lfsConfigFactory,
      LfsRepositoryResolver repoResolver,
      LfsAuthUserProvider userProvider) {
    this.projectCache = projectCache;
    this.permissionBackend = permissionBackend;
    this.lfsConfigFactory = lfsConfigFactory;
    this.repoResolver = repoResolver;
    this.userProvider = userProvider;
    ExtLogger.finer("enter");
  }

	private static final String CONTENTTYPE_VND_GIT_LFS_JSON =
			"application/vnd.git-lfs+json; charset=utf-8"; //$NON-NLS-1$
	private static final int SC_RATE_LIMIT_EXCEEDED = 429;
  private static final int SC_BANDWIDTH_LIMIT_EXCEEDED = 509;
  private void sendError(HttpServletResponse rsp, Writer writer, int status,
			String message) {
		rsp.setStatus(status);
		LfsGson.toJson(message, writer);
	}
	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse res)
			throws ServletException, IOException {
		Writer w = new BufferedWriter(
				new OutputStreamWriter(res.getOutputStream(), UTF_8));

		Reader r = new BufferedReader(
				new InputStreamReader(req.getInputStream(), UTF_8));
		LfsRequest request = LfsGson.fromJson(r, LfsRequest.class);
		String path = req.getPathInfo();
    ExtRepoPath repoPath = new ExtRepoPath(path);

		res.setContentType(CONTENTTYPE_VND_GIT_LFS_JSON);
		LargeFileRepository repo = null;
		try {
      ExtLogger.finer("new doPost " + path + " operation "+request.getOperation());

			repo = getLargeFileRepository(request, path,
					req.getHeader(HDR_AUTHORIZATION));
			if (repo == null) {
				String error = MessageFormat
						.format(LfsText.get().lfsFailedToGetRepository, path);
				ExtLogger.severe("err " + error);
				throw new LfsException(error);
			}
			res.setStatus(SC_OK);
			ExtTransferHandler handler = ExtTransferHandler
					.forOperation(request.getOperation(), repoPath, repo, request.getObjects());
			LfsGson.toJson(handler.process(), w);
		} catch (LfsValidationError e) {
			sendError(res, w, SC_UNPROCESSABLE_ENTITY, e.getMessage());
		} catch (LfsRepositoryNotFound e) {
			sendError(res, w, SC_NOT_FOUND, e.getMessage());
		} catch (LfsRepositoryReadOnly e) {
			sendError(res, w, SC_FORBIDDEN, e.getMessage());
		} catch (LfsRateLimitExceeded e) {
			sendError(res, w, SC_RATE_LIMIT_EXCEEDED, e.getMessage());
		} catch (LfsBandwidthLimitExceeded e) {
			sendError(res, w, SC_BANDWIDTH_LIMIT_EXCEEDED, e.getMessage());
		} catch (LfsInsufficientStorage e) {
			sendError(res, w, SC_INSUFFICIENT_STORAGE, e.getMessage());
		} catch (LfsUnavailable e) {
			sendError(res, w, SC_SERVICE_UNAVAILABLE, e.getMessage());
		} catch (LfsUnauthorized e) {
			sendError(res, w, SC_UNAUTHORIZED, e.getMessage());
		} catch (LfsException e) {
			sendError(res, w, SC_INTERNAL_SERVER_ERROR, e.getMessage());
		} finally {
			w.flush();
		}
	}
  
//

  @Override
  protected LargeFileRepository getLargeFileRepository(LfsRequest request, String path, String auth)
      throws LfsException {
    String pathInfo = path.startsWith("/") ? path : "/" + path;
    Matcher matcher = URL_PATTERN.matcher(pathInfo);
    if (!matcher.matches()) {
      throw new LfsException("no repository at " + pathInfo);
    }

    String projName = matcher.group(1);

    ExtLogger.info("projName="+projName);

    Project.NameKey project = Project.nameKey(ProjectUtil.stripGitSuffix(projName));
    Optional<ProjectState> state = projectCache.get(project);
    if (!state.isPresent() || state.get().getProject().getState() == HIDDEN) {
      throw new LfsRepositoryNotFound(project.get());
    }
    authorizeUser(
        userProvider.getUser(auth, projName, request.getOperation()), state.get(), request);

    if (request.isUpload() && state.get().getProject().getState() == READ_ONLY) {
      throw new LfsRepositoryReadOnly(project.get());
    }

    LfsProjectConfigSection config = lfsConfigFactory.getProjectsConfig().getForProject(project);
    // Only accept requests for projects where LFS is enabled.
    // No config means we default to "not enabled".
    if (config != null && config.isEnabled()) {
      // For uploads, check object sizes against limit if configured
      if (request.isUpload()) {
        if (config.isReadOnly()) {
          throw new LfsRepositoryReadOnly(project.get());
        }

        long maxObjectSize = config.getMaxObjectSize();
        if (maxObjectSize > 0) {
          for (LfsObject object : request.getObjects()) {
            if (object.getSize() > maxObjectSize) {
              throw new LfsValidationError(
                  String.format(
                      "size of object %s (%d bytes) exceeds limit (%d bytes)",
                      object.getOid(), object.getSize(), maxObjectSize));
            }
          }
        }
      }

      return repoResolver.get(project, config.getBackend());
    }

    throw new LfsUnavailable(project.get());
  }

  private void authorizeUser(CurrentUser user, ProjectState state, LfsRequest request)
      throws LfsUnauthorized {
    Project.NameKey projectName = state.getNameKey();
    if ((request.isDownload()
            && !permissionBackend.user(user).project(projectName).testOrFalse(ACCESS))
        || (request.isUpload()
            && !permissionBackend
                .user(user)
                .project(projectName)
                .testOrFalse(PUSH_AT_LEAST_ONE_REF))) {
      String op = request.getOperation().toLowerCase();
      String project = state.getProject().getName();
      String userName = user.getUserName().orElse("anonymous");
      ExtLogger.fineref(
          "operation %s unauthorized for user %s on project %s", op, userName, project);
      throw new LfsUnauthorized(op, project);
    }
  }
}
