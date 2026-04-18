/*

Baseon: bb309748c7ead580f09dd685ba8d1374a539cb1c

*/


/*
 * Copyright (C) 2015, Sasa Zivkov <sasa.zivkov@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package com.googlesource.gerrit.plugins.lfs;

import static org.eclipse.jgit.lfs.lib.Constants.DOWNLOAD;
import static org.eclipse.jgit.lfs.lib.Constants.UPLOAD;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static org.eclipse.jgit.lfs.lib.Constants.VERIFY;

import static com.google.gerrit.extensions.api.lfs.LfsDefinitions.LFS_OBJECTS_PATH;
import static com.google.gerrit.extensions.api.lfs.LfsDefinitions.LFS_URL_REGEX_TEMPLATE;
import static com.google.gerrit.extensions.client.ProjectState.HIDDEN;
import static com.google.gerrit.extensions.client.ProjectState.READ_ONLY;
import static com.google.gerrit.server.permissions.ProjectPermission.ACCESS;
import static com.google.gerrit.server.permissions.ProjectPermission.PUSH_AT_LEAST_ONE_REF;

import com.google.gerrit.extensions.annotations.PluginCanonicalWebUrl;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.googlesource.gerrit.plugins.lfs.LfsBackend;
import com.googlesource.gerrit.plugins.lfs.LfsConfigurationFactory;
import com.googlesource.gerrit.plugins.lfs.auth.AuthInfo;
import com.googlesource.gerrit.plugins.lfs.auth.ExpiringAction;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.time.Instant;

import org.eclipse.jgit.lfs.lib.LongObjectId;
import org.eclipse.jgit.lfs.lib.AnyLongObjectId;
import org.eclipse.jgit.lfs.server.Response;
import org.eclipse.jgit.lfs.server.fs.FileLfsRepository;
import org.eclipse.jgit.lfs.server.LargeFileRepository;
import org.eclipse.jgit.lfs.server.LfsObject;
import org.eclipse.jgit.lfs.server.internal.LfsServerText;

import com.googlesource.gerrit.plugins.lfs.fs.LocalLargeFileRepository;

// Sampee
import com.googlesource.gerrit.plugins.lfs.ExtRepoPath;

//

abstract class ExtTransferHandler {

	static ExtTransferHandler forOperation(String operation,
			ExtRepoPath repoPath,
			LargeFileRepository repository, List<LfsObject> objects) {
		switch (operation) {
		case UPLOAD:
			return new Upload(repository, repoPath, objects);
		case DOWNLOAD:
			return new Download(repository, repoPath, objects);
		case VERIFY:
		default:
			throw new UnsupportedOperationException(MessageFormat.format(
					LfsServerText.get().unsupportedOperation, operation));
		}
	}

	private static class Upload extends ExtTransferHandler {
		Upload(LargeFileRepository repository,
				ExtRepoPath repoPath,
				List<LfsObject> objects) {
			super(repository, repoPath, objects);
		}

		@Override
		Response.Body process() throws IOException {
			Response.Body body = new Response.Body();
			if (!objects.isEmpty()) {
				body.objects = new ArrayList<>();
				for (LfsObject o : objects) {
					addObjectInfo(body, o);
				}
			}
			return body;
		}

		private void addObjectInfo(Response.Body body, LfsObject o)
				throws IOException {
			Response.ObjectInfo info = new Response.ObjectInfo();
			body.objects.add(info);
			info.oid = o.getOid();
			info.size = o.getSize();

			LongObjectId oid = LongObjectId.fromString(o.getOid());
			boolean isNeedUpload = false;
			if (
				// false &&
				repository instanceof LocalLargeFileRepository){
				LocalLargeFileRepository localFsRepo = (LocalLargeFileRepository) repository;
				if (localFsRepo.getSize(this.repoPath, oid) == -1) {
					info.actions = new HashMap<>();
					info.actions.put(UPLOAD,
							localFsRepo.getUploadAction(this.repoPath, oid, o.getSize()));
					isNeedUpload = true;
				}
			} else {
				if (repository.getSize(oid) == -1) {
					info.actions.put(UPLOAD,
						repository.getUploadAction(oid, o.getSize()));
					isNeedUpload = true;
				}
			}

			if(isNeedUpload){	
				Response.Action verify = repository.getVerifyAction(oid);
				if (verify != null) {
					info.actions.put(VERIFY, verify);
				}
			}
		}
	}

	private static class Download extends ExtTransferHandler {
		Download(LargeFileRepository repository,
				ExtRepoPath repoPath,
				List<LfsObject> objects) {
			super(repository, repoPath, objects);
		}

		@Override
		Response.Body process() throws IOException {
			Response.Body body = new Response.Body();
			if (!objects.isEmpty()) {
				body.objects = new ArrayList<>();
				for (LfsObject o : objects) {
					addObjectInfo(body, o);
				}
			}
			return body;
		}

		private void addObjectInfo(Response.Body body, LfsObject o)
				throws IOException {
			Response.ObjectInfo info = new Response.ObjectInfo();
			body.objects.add(info);
			info.oid = o.getOid();
			info.size = o.getSize();

			boolean isFailed = true;
			LongObjectId oid = LongObjectId.fromString(o.getOid());
			if (
				// false &&
				repository instanceof LocalLargeFileRepository) {
				LocalLargeFileRepository localFsRepo = (LocalLargeFileRepository) repository;
				if (localFsRepo.getSize(this.repoPath, oid) >= 0) {
					info.actions = new HashMap<>();
					info.actions.put(DOWNLOAD,
							localFsRepo.getDownloadAction(this.repoPath, oid));
					isFailed = false;
				}
			}
			else if(repository.getSize(oid) >= 0) {
				info.actions = new HashMap<>();
				info.actions.put(DOWNLOAD,
						repository.getDownloadAction(oid));
				isFailed = false;
			} 

			if (isFailed) {
				info.error = new Response.Error();
				info.error.code = SC_NOT_FOUND;
				info.error.message = MessageFormat.format(
						LfsServerText.get().objectNotFound,
						oid.getName());
			}
		}
	}

	final LargeFileRepository repository;

	final List<LfsObject> objects;

	final ExtRepoPath repoPath;

	ExtTransferHandler(LargeFileRepository repository,
			ExtRepoPath repoPath,
			List<LfsObject> objects) {
		this.repository = repository;
		this.repoPath = repoPath;
		this.objects = objects;
		ExtLogger.infof("constructor repo=%s, objects=%d", repoPath, objects.size());
	}

	abstract Response.Body process() throws IOException;
}
