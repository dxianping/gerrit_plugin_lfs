// Copyright (C) 2015 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.lfs.fs;

import static org.eclipse.jgit.lfs.lib.Constants.DOWNLOAD;
import static org.eclipse.jgit.lfs.lib.Constants.UPLOAD;
import static org.eclipse.jgit.util.HttpSupport.HDR_AUTHORIZATION;

import com.google.common.base.Strings;
import com.google.common.net.HttpHeaders;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.Optional;
import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.http.HttpStatus;
import org.eclipse.jgit.lfs.lib.AnyLongObjectId;
import org.eclipse.jgit.lfs.server.fs.FileLfsServlet;
import org.eclipse.jgit.lfs.server.fs.ObjectDownloadListener;
import org.eclipse.jgit.lfs.server.fs.ObjectUploadListener;
import org.eclipse.jgit.lfs.server.internal.LfsServerText;

// Sampee

import org.eclipse.jgit.lfs.errors.InvalidLongObjectIdException;
import org.eclipse.jgit.lfs.lib.AnyLongObjectId;
import org.eclipse.jgit.lfs.lib.Constants;
import org.eclipse.jgit.lfs.lib.LongObjectId;
import com.googlesource.gerrit.plugins.lfs.ExtObjectDownloadListener;
import com.googlesource.gerrit.plugins.lfs.ExtRepoPath;
import com.googlesource.gerrit.plugins.lfs.ExtObjectUploadListener;
import com.googlesource.gerrit.plugins.lfs.ExtLogger;

//
public class LfsFsContentServlet extends FileLfsServlet {
  public interface Factory {
    LfsFsContentServlet create(LocalLargeFileRepository largeFileRepository);
  }

  private static final long serialVersionUID = 1L;

  private final LfsFsRequestAuthorizer authorizer;
  private final LocalLargeFileRepository repository;
  private final long timeout;
  @Inject
  public LfsFsContentServlet(
      LfsFsRequestAuthorizer authorizer, @Assisted LocalLargeFileRepository repository) {
    super(repository, 0);
    this.authorizer = authorizer;
    this.repository = repository;
    this.timeout = 0;

    ExtLogger.finer("enter");
  }

  @Override
  protected void doHead(HttpServletRequest req, HttpServletResponse rsp)
      throws ServletException, IOException {
    String verifyId = req.getHeader(HttpHeaders.IF_NONE_MATCH);
    if (Strings.isNullOrEmpty(verifyId)) {
      ExtLogger.finer("no verifyId ");
      doGet(req, rsp);
      return;
    }
    ExtLogger.info("verifyId="+verifyId);

    ExtRepoPath repoPath = new ExtRepoPath(req.getPathInfo());

    Optional<AnyLongObjectId> obj = validateGetRequest(repoPath, req, rsp);
    if (obj.isPresent() && obj.get().getName().equalsIgnoreCase(verifyId)) {
      rsp.addHeader(HttpHeaders.ETAG, obj.get().getName());
      rsp.setStatus(HttpStatus.SC_NOT_MODIFIED);
      return;
    }
    getObject(repoPath, req, rsp, obj);
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse rsp)
      throws ServletException, IOException {
    ExtLogger.finer("enter");
    
    ExtRepoPath repoPath = new ExtRepoPath(true, req.getPathInfo());
    Optional<AnyLongObjectId> obj = validateGetRequest(repoPath, req, rsp);
    getObject(repoPath, req, rsp, obj);
  }

  @Override
  protected void doPut(HttpServletRequest req, HttpServletResponse rsp)
      throws ServletException, IOException {
    ExtLogger.info("getPathInfo: " + req.getPathInfo());
    ExtRepoPath repoPath = new ExtRepoPath(true, req.getPathInfo());
    AnyLongObjectId id = getObjectToTransfer(repoPath, req, rsp);
    if (id == null) {
      return;
    }

    if (!authorizer.verifyAuthInfo(req.getHeader(HDR_AUTHORIZATION), UPLOAD, id)) {
      ExtLogger.infof("doPut unauthorized id= %s", id.toString());
      sendError(
          rsp,
          HttpStatus.SC_UNAUTHORIZED,
          MessageFormat.format(
              LfsServerText.get().failedToCalcSignature, "Invalid authorization token"));
      return;
    }

    AsyncContext context = req.startAsync();
    context.setTimeout(timeout);
    req.getInputStream()
        .setReadListener(new ExtObjectUploadListener(repoPath, repository, context, req, rsp, id));
  }

  protected AnyLongObjectId getObjectToTransfer(ExtRepoPath repoPath, HttpServletRequest req,
			HttpServletResponse rsp) throws IOException {
    String info = repoPath.getId();
		int length = 1 + Constants.LONG_OBJECT_ID_STRING_LENGTH;

		if (info.length() != length) {
			sendError(rsp, HttpStatus.SC_UNPROCESSABLE_ENTITY, MessageFormat
					.format(LfsServerText.get().invalidPathInfo, info));
      ExtLogger.severef("invalid path info %s", info);
			return null;
		}
		try {
			return LongObjectId.fromString(info.substring(1, length));
		} catch (InvalidLongObjectIdException e) {
			sendError(rsp, HttpStatus.SC_UNPROCESSABLE_ENTITY, e.getMessage());
      ExtLogger.severef("invalid object id %s", info.substring(1, length));
    	return null;
		}
	}

  private Optional<AnyLongObjectId> validateGetRequest(
    ExtRepoPath repoPath, HttpServletRequest req, HttpServletResponse rsp) throws IOException {
    AnyLongObjectId obj = getObjectToTransfer(repoPath, req, rsp);
    if (obj == null) {
      ExtLogger.severe("no object to transfer ");
      return Optional.empty();
    }
  
    if(repository.getSize(repoPath,obj)==-1) {
        sendError(
            rsp,
            HttpStatus.SC_NOT_FOUND,
            MessageFormat.format(LfsServerText.get().objectNotFound, obj.getName()));
        return Optional.empty();
    }

    if (!authorizer.verifyAuthInfo(req.getHeader(HDR_AUTHORIZATION), DOWNLOAD, obj)) {
      sendError(
          rsp,
          HttpStatus.SC_UNAUTHORIZED,
          MessageFormat.format(
              LfsServerText.get().failedToCalcSignature, "Invalid authorization token"));
      return Optional.empty();
    }
    return Optional.of(obj);
  }

  private void getObject(
      ExtRepoPath repoPath, HttpServletRequest req, HttpServletResponse rsp, Optional<AnyLongObjectId> obj)
      throws IOException {
    if (obj.isPresent()) {
      AsyncContext context = req.startAsync();
      context.setTimeout(timeout);
      rsp.getOutputStream()
          .setWriteListener(new ExtObjectDownloadListener(repoPath, repository, context, rsp, obj.get()));
    } else {
      ExtLogger.severe("obj not present "+ repoPath);
    }
  }
}
