/**
 * Copyright (C) 2010-2012 Andrei Pozolotin <Andrei.Pozolotin@gmail.com>
 *
 * All rights reserved. Licensed under the OSI BSD License.
 *
 * http://www.opensource.org/licenses/bsd-license.php
 */
package com.carrotgarden.nexus.aws.s3.publish.storage;

import java.io.File;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.nexus.mime.MimeSupport;
import org.sonatype.nexus.proxy.LocalStorageException;
import org.sonatype.nexus.proxy.ResourceStoreRequest;
import org.sonatype.nexus.proxy.item.LinkPersister;
import org.sonatype.nexus.proxy.item.StorageFileItem;
import org.sonatype.nexus.proxy.item.StorageItem;
import org.sonatype.nexus.proxy.repository.Repository;
import org.sonatype.nexus.proxy.storage.UnsupportedStorageOperationException;
import org.sonatype.nexus.proxy.storage.local.LocalRepositoryStorage;
import org.sonatype.nexus.proxy.storage.local.fs.DefaultFSLocalRepositoryStorage;
import org.sonatype.nexus.proxy.storage.local.fs.FSPeer;
import org.sonatype.nexus.proxy.wastebasket.Wastebasket;

import com.carrotgarden.nexus.aws.s3.publish.amazon.AmazonService;
import com.carrotgarden.nexus.aws.s3.publish.config.ConfigEntry;
import com.carrotgarden.nexus.aws.s3.publish.config.ConfigEntryList;
import com.carrotgarden.nexus.aws.s3.publish.config.ConfigResolver;
import com.carrotgarden.nexus.aws.s3.publish.config.ConfigState;
import com.carrotgarden.nexus.aws.s3.publish.mail.CarrotMailer;
import com.carrotgarden.nexus.aws.s3.publish.metrics.StorageReporter;
import com.carrotgarden.nexus.aws.s3.publish.util.AmazonHelp;
import com.yammer.metrics.Metrics;

/**
 * custom local/remote store
 * <p>
 * store both on local file system and on the amazon bucket
 * <p>
 * single file item can be stored to multiple buckets
 * <p>
 * fail if any store operation fails
 */
@Singleton
@Named(CarrotRepositoryStorage.NAME)
public class CarrotRepositoryStorage extends DefaultFSLocalRepositoryStorage
		implements LocalRepositoryStorage {

	public static final String NAME = "carrot.repo.storage";

	protected final Logger log = LoggerFactory.getLogger(getClass());

	private final ConfigResolver resolver;
	private final StorageReporter reporter;
	private final CarrotMailer mailer;

	@Inject
	public CarrotRepositoryStorage( //
			final CarrotMailer mailer, //
			// final StorageReporter reporter, //
			final ConfigResolver resolver, //
			final Wastebasket wastebasket, //
			final LinkPersister linkPersister, //
			final MimeSupport mimeSupport, //
			final FSPeer fsPeer //
	) {

		super(wastebasket, linkPersister, mimeSupport, fsPeer);

		this.mailer = mailer;
		this.resolver = resolver;
		this.reporter = new StorageReporter(Metrics.defaultRegistry());

	}

	@Override
	public String getProviderId() {
		return NAME;
	}

	@Override
	public void storeItem(final Repository repository, final StorageItem itemAny)
			throws UnsupportedStorageOperationException, LocalStorageException {

		final boolean isFileItem = itemAny instanceof StorageFileItem;

		if (!isFileItem) {
			reporter.amazonIgnoredFileCount.inc();
			super.storeItem(repository, itemAny);
			return;
		}

		try {

			final String repoId = repository.getId();

			final StorageFileItem item = (StorageFileItem) itemAny;

			final ResourceStoreRequest request = item.getResourceStoreRequest();

			final File file = getFileFromBase(repository, request);

			reporter.repoFilePeek.add(file);

			/** store local */

			super.storeItem(repository, item);

			/** store all remote */

			final String path = item.getPath();

			final ConfigEntryList entryList = resolver.entryList(repoId);

			boolean isSaved = true;

			for (final ConfigEntry entry : entryList) {

				if (ConfigState.ENABLED == entry.configState()) {

					if (entry.isExcluded(path)) {
						reporter.amazonIgnoredFileCount.inc();
						continue;
					}

					final AmazonService amazonService = entry.amazonService();

					isSaved &= AmazonHelp.storeItem( //
							amazonService, repository, item, file, log);

					if (isSaved) {
						reporter.amazonPublishedFileCount.inc();
						reporter.amazonPublishedFileSize.inc(file.length());
						continue;
					} else {
						// TODO
						// mailer.send(email, subject, message);
						break;
					}

				} else {

					/** no stats */
					continue;

				}

			}

			if (isSaved) {
				return;
			}

			/** revert local */
			super.shredItem(repository, request);

			throw new LocalStorageException("amazon provider failure");

		} catch (final Exception e) {

			reporter.amazonFailedFileCount.inc();

			throw new LocalStorageException(e);

		}

	}

}