/****************************************************************************
 * Copyright (c) 2008-2014 Matthew Ballance and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/.
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 *
 * Contributors:
 *     Armond Paiva - initial contributor
 ****************************************************************************/

package org.sveditor.ui.views.diagram;

import org.eclipse.zest.layouts.LayoutAlgorithm;
import org.eclipse.zest.layouts.interfaces.LayoutContext;

/**
 * Layout algorithm that intentionally does nothing, leaving nodes where they are.
 * Uses the Zest 2 LayoutAlgorithm API (GEF Classic 3.x).
 */
public class LeaveEmBeLayoutAlgoritm implements LayoutAlgorithm {

	public LeaveEmBeLayoutAlgoritm(int styles) { }

	@Override
	public void setLayoutContext(LayoutContext context) { }

	@Override
	public void applyLayout(boolean clean) { }

}
