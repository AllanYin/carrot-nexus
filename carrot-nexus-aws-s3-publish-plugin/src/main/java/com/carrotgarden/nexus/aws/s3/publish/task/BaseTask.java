/**
 * Copyright (C) 2010-2012 Andrei Pozolotin <Andrei.Pozolotin@gmail.com>
 *
 * All rights reserved. Licensed under the OSI BSD License.
 *
 * http://www.opensource.org/licenses/bsd-license.php
 */
package com.carrotgarden.nexus.aws.s3.publish.task;

import javax.inject.Named;

import org.sonatype.nexus.scheduling.AbstractNexusTask;

public abstract class BaseTask extends AbstractNexusTask<Object> {

	/** nexus task name/type convention */
	{
		final Named anno = getClass().getAnnotation(Named.class);
		if (anno == null) {
			throw new IllegalStateException("@Named is missing");
		}
		if (!anno.value().equals(getClass().getSimpleName())) {
			throw new IllegalStateException("@Named must match class name");
		}
	}

}