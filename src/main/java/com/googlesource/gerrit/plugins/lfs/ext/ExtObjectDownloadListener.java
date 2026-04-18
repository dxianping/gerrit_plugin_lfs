/*

Baseon: bb309748c7ead580f09dd685ba8d1374a539cb1c

*/


/*
 * Copyright (C) 2015, Matthias Sohn <matthias.sohn@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package com.googlesource.gerrit.plugins.lfs;
// package org.eclipse.jgit.lfs.server.fs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.AsyncContext;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletResponse;

import org.apache.http.HttpStatus;
import org.eclipse.jgit.lfs.lib.AnyLongObjectId;
import org.eclipse.jgit.lfs.lib.Constants;
import org.eclipse.jgit.util.HttpSupport;

// Sampee
import com.googlesource.gerrit.plugins.lfs.ExtLogger;
import org.eclipse.jgit.lfs.server.fs.FileLfsRepository;
import com.googlesource.gerrit.plugins.lfs.ExtRepoPath;
import com.googlesource.gerrit.plugins.lfs.fs.LocalLargeFileRepository;
import org.eclipse.jgit.lfs.server.fs.FileLfsServlet;
import org.eclipse.jgit.lfs.server.internal.LfsGson;
import org.eclipse.jgit.lfs.server.internal.LfsServerText;
import java.io.PrintWriter;
//
/**
 * Handle asynchronous large object download.
 *
 * @since 4.7
 */
public class ExtObjectDownloadListener implements WriteListener {

	private final AsyncContext context;

	private final HttpServletResponse response;

	private final ServletOutputStream out;

	private final ReadableByteChannel in;

	private final WritableByteChannel outChannel;

	private ByteBuffer buffer = ByteBuffer.allocateDirect(8192);

	/**
	 * <p>
	 * Constructor for ExtObjectDownloadListener.
	 * </p>
	 *
	 * @param repository
	 *            the repository storing large objects
	 * @param context
	 *            the servlet asynchronous context
	 * @param response
	 *            the servlet response
	 * @param id
	 *            id of the object to be downloaded
	 * @throws java.io.IOException
	 *             if an IO error occurred
	 * @since 7.0
	 */
	public ExtObjectDownloadListener(ExtRepoPath repoPath, FileLfsRepository _repository,
			AsyncContext context, HttpServletResponse response,
			AnyLongObjectId id) throws IOException {
        LocalLargeFileRepository repository = (LocalLargeFileRepository)_repository;
		this.context = context;
		this.response = response;
		this.in = repository.getReadChannel(repoPath, id);
		this.out = response.getOutputStream();
		this.outChannel = Channels.newChannel(out);

		ExtLogger.fineref("constructor id= %s", id.toString());
		response.addHeader(HttpSupport.HDR_CONTENT_LENGTH,
				String.valueOf(repository.getSize(repoPath, id)));
		response.setContentType(Constants.HDR_APPLICATION_OCTET_STREAM);
	}

	/**
	 * {@inheritDoc}
	 *
	 * Write file content
	 */
	@SuppressWarnings("Finally")
	@Override
	public void onWritePossible() throws IOException {
		while (out.isReady()) {
			try {
				buffer.clear();
				if (in.read(buffer) < 0) {
					buffer = null;
				} else {
					buffer.flip();
				}
			} catch (Throwable t) {
				ExtLogger.severe(t.getMessage());
				buffer = null;
			} finally {
				if (buffer != null) {
					outChannel.write(buffer);
				} else {
					try {
						in.close();
					} catch (IOException e) {
						ExtLogger.severe(e.getMessage());
					}
					try {
						out.close();
					} finally {
						ExtLogger.info("onWritePossible complete");
						context.complete();
					}
					// This is need to avoid endless loop in recent Jetty versions.
					// That's because out.isReady() is returning true for already
					// closed streams and because out.close() doesn't throw any
					// exception any more when trying to close already closed stream.
					return;
				}
			}
		}
	}

    public static void sendError(HttpServletResponse rsp, int status, String message)
			throws IOException {
		if (rsp.isCommitted()) {
			rsp.getOutputStream().close();
			return;
		}
		rsp.reset();
		rsp.setStatus(status);
		try (PrintWriter writer = rsp.getWriter()) {
			LfsGson.toJson(message, writer);
			writer.flush();
		}
		rsp.flushBuffer();
	}

	/**
	 * {@inheritDoc}
	 *
	 * Handle errors
	 */
	@Override
	public void onError(Throwable e) {
		try {
			sendError(response,
					HttpStatus.SC_INTERNAL_SERVER_ERROR, e.getMessage());
			context.complete();
			in.close();
		} catch (IOException ex) {
			ExtLogger.severe(ex.getMessage());
		}
	}
}
