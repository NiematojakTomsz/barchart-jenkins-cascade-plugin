package com.barchart.jenkins.cascade;

import hudson.model.Action;

public class AbstractAction implements Action {

	private String displayName;
	private String iconFileName;
	private String urlName;

	protected AbstractAction(final String... array) {
		if (array.length > 0) {
			displayName = array[0];
		}
		if (array.length > 1) {
			iconFileName = array[1];
		}
		if (array.length > 2) {
			urlName = array[2];
		}
	}

	public String getIconFileName() {
		return iconFileName;
	}

	public String getDisplayName() {
		return displayName;
	}

	public String getUrlName() {
		return urlName;
	}

	@Override
	public String toString() {
		String result = "";
		if (displayName != null) {
			result = result + displayName;
		}
		if (iconFileName != null) {
			result = result + " [" + iconFileName + "]";
		}
		if (urlName != null) {
			result = result + " " + urlName;
		}
		return result;
	}

}