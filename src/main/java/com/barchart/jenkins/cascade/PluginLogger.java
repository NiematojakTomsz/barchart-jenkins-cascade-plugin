/**
 * Copyright (C) 2013 Barchart, Inc. <http://www.barchart.com/>
 *
 * All rights reserved. Licensed under the OSI BSD License.
 *
 * http://www.opensource.org/licenses/bsd-license.php
 */
package com.barchart.jenkins.cascade;

import hudson.model.TaskListener;

/**
 * Convenience build logger.
 * 
 * @author Andrei Pozolotin
 */
public class PluginLogger {

	private final TaskListener listener;

	public PluginLogger(final TaskListener listener) {
		this.listener = listener;
	}

	/** Log text with plug-in prefix. */
	public void text(final String text) {
		listener.getLogger()
				.println(PluginConstants.LOGGER_PREFIX + " " + text);
	}

}
