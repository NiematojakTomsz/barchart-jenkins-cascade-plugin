/**
 * Copyright (C) 2013 Barchart, Inc. <http://www.barchart.com/>
 *
 * All rights reserved. Licensed under the OSI BSD License.
 *
 * http://www.opensource.org/licenses/bsd-license.php
 */
package com.barchart.jenkins.cascade;


/**
 * Action used to skip SCM checkout.
 * 
 * @author Andrei Pozolotin
 */
public class CheckoutSkipAction extends AbstractAction {

	@Override
	public String toString() {
		return "Ignore SCM Checkout.";
	}

}
