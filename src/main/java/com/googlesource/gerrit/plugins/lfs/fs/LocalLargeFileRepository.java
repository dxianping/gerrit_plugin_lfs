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

import com.google.gerrit.extensions.annotations.PluginCanonicalWebUrl;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.googlesource.gerrit.plugins.lfs.LfsBackend;
import com.googlesource.gerrit.plugins.lfs.LfsConfigurationFactory;
import com.googlesource.gerrit.plugins.lfs.auth.AuthInfo;
import com.googlesource.gerrit.plugins.lfs.auth.ExpiringAction;
import java.io.IOException;
import java.time.Instant;
import org.eclipse.jgit.lfs.lib.AnyLongObjectId;
import org.eclipse.jgit.lfs.server.Response;
import org.eclipse.jgit.lfs.server.fs.FileLfsRepository;

// Sampee
import static org.eclipse.jgit.util.HttpSupport.HDR_AUTHORIZATION;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import org.eclipse.jgit.lfs.lib.Constants;
import java.nio.channels.ReadableByteChannel;
import org.eclipse.jgit.lfs.internal.AtomicObjectOutputStream;
import java.nio.channels.FileChannel;
import com.googlesource.gerrit.plugins.lfs.ExtRepoPath;
import com.googlesource.gerrit.plugins.lfs.ExtLogger;

//

public class LocalLargeFileRepository extends FileLfsRepository {

  public interface Factory {
    LocalLargeFileRepository create(LfsBackend backendConfig);
  }

  private static final String CONTENT_PATH_TEMPLATE = "content/%s/";
  private static final int DEFAULT_EXPIRATION_SECONDS = 10;

  private final String servletUrlPattern;
  private final LfsFsRequestAuthorizer authorizer;
  private final Long expiresIn;

  @Inject
  LocalLargeFileRepository(
      LfsFsDataDirectoryManager dataDirManager,
      LfsConfigurationFactory configFactory,
      LfsFsRequestAuthorizer authorizer,
      @PluginCanonicalWebUrl String url,
      @Assisted LfsBackend backend)
      throws IOException {
    super(getContentUrl(url, backend), dataDirManager.ensureForBackend(backend));
    this.authorizer = authorizer;
    this.servletUrlPattern = "/" + getContentPath(backend) + "*";
    this.expiresIn =
        (long)
            configFactory
                .getGlobalConfig()
                .getInt(
                    backend.type.name(),
                    backend.name,
                    "expirationSeconds",
                    DEFAULT_EXPIRATION_SECONDS);

    ExtLogger.finer(
      "constructor url= " + url +
      " getContentUrl = " + getContentUrl(url, backend) +
      " servletUrlPattern= " + servletUrlPattern +
      " expiresIn= " + expiresIn +
      " backend.name= " + backend.name +
      " backend.type= " + backend.type);
  }

  public String getServletUrlPattern() {
    return servletUrlPattern;
  }

  @Override
  public Response.Action getDownloadAction(AnyLongObjectId id) {
    ExtLogger.finer("id= %s"+id.toString());
    Response.Action action = super.getDownloadAction(id);
    AuthInfo authInfo = authorizer.generateAuthInfo(DOWNLOAD, id, Instant.now(), expiresIn);
    Response.Action act= new ExpiringAction(action.href, authInfo);
    ExtLogger.info("download href=" + act.href);
    return act;
  }

  String byteToHex(long b) {
        return String.format("%02x", b & 0xFF);
  }

	public Path getPath(ExtRepoPath repoPath, AnyLongObjectId id) {
		StringBuilder s = new StringBuilder(
				Constants.LONG_OBJECT_ID_STRING_LENGTH + 6);
		s.append(repoPath.getShortRepoHash()).append('/');
		s.append(byteToHex(id.getByte(0))).append('/');
		s.append(byteToHex(id.getByte(1))).append('/');
		s.append(id.name());
		Path path= super.getDir().resolve(s.toString());
    ExtLogger.fineref(" resolved path=%s repo=%s, getDir=%s", path.toString(), repoPath,super.getDir().toString());
    return path;
	}

  public long getSize(ExtRepoPath repoPath, AnyLongObjectId id) throws IOException {
		Path p = getPath(repoPath, id);
		if (Files.exists(p)) {
			ExtLogger.fineref("File size=%d Repo=%s path=%s", Files.size(p), repoPath, p.toString());
			return Files.size(p);
		}
    ExtLogger.severef("File not found Repo=%s path=%s", repoPath, p.toString());
		return -1;
	}

  private Response.Action GetAction(ExtRepoPath repoPath, AnyLongObjectId id) {
		Response.Action a = new Response.Action();
		a.href = getUrl() + repoPath.getShortRepoHash() + "/" + id.getName();
		a.header = Collections.singletonMap(HDR_AUTHORIZATION, "not:required"); //$NON-NLS-1$
		return a;
	}

  public
  ReadableByteChannel getReadChannel(ExtRepoPath repoPath, AnyLongObjectId id)
			throws IOException {
		ExtLogger.fineref("channel id= %s", id.toString());
		return FileChannel.open(getPath(repoPath, id), StandardOpenOption.READ);
	}

	public AtomicObjectOutputStream getOutputStream(ExtRepoPath repoPath, AnyLongObjectId id)
			throws IOException {
		Path path = getPath(repoPath, id);
		Path parent = path.getParent();
		ExtLogger.fineref("output path=%s", path.toString());
		if (parent != null) {
			Files.createDirectories(parent);
		}
		return new AtomicObjectOutputStream(path, id);
	}

  public Response.Action getDownloadAction(ExtRepoPath repoPath, AnyLongObjectId id) {
    ExtLogger.fineref("require dl id= %s", id.toString());
    Response.Action action = GetAction(repoPath, id);
    AuthInfo authInfo = authorizer.generateAuthInfo(DOWNLOAD, id, Instant.now(), expiresIn);
    Response.Action act= new ExpiringAction(action.href, authInfo);
    ExtLogger.fineref("set download href= %s", act.href);
    return act;
  }

  public Response.Action getUploadAction(ExtRepoPath repoPath, AnyLongObjectId id, long size) {
    ExtLogger.fineref("upload repo=%s id= %s size=%d", repoPath, id.toString(),size);
    Response.Action action = GetAction(repoPath, id);
    AuthInfo authInfo = authorizer.generateAuthInfo(UPLOAD, id, Instant.now(), expiresIn);
    return new ExpiringAction(action.href, authInfo);
  }

  @Override
  public Response.Action getUploadAction(AnyLongObjectId id, long size) {
    ExtLogger.fineref("id= %s size=%d", id.toString(),size);
    Response.Action action = super.getUploadAction(id, size);
    AuthInfo authInfo = authorizer.generateAuthInfo(UPLOAD, id, Instant.now(), expiresIn);
    return new ExpiringAction(action.href, authInfo);
  }

  private static String getContentUrl(String url, LfsBackend backend) {
    ExtLogger.fineref("url= %s", url);
    // for default FS we still need to define namespace as otherwise it would
    // interfere with rest of FS backends
    return url + (url.endsWith("/") ? "" : "/") + getContentPath(backend);
  }

  private static String getContentPath(LfsBackend backend) {
    ExtLogger.fineref("backend= %s", backend.name);
    return String.format(CONTENT_PATH_TEMPLATE, backend.name());
  }
}
