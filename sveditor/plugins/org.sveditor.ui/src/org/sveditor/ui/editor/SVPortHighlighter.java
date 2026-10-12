/****************************************************************************
 * Copyright (c) 2008-2026 Matthew Ballance and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/.
 *
 * SPDX-License-Identifier: EPL-2.0
 ****************************************************************************/

package org.sveditor.ui.editor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.BadPartitioningException;
import org.eclipse.jface.text.BadPositionCategoryException;
import org.eclipse.jface.text.DefaultPositionUpdater;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension3;
import org.eclipse.jface.text.IPositionUpdater;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextPresentationListener;
import org.eclipse.jface.text.ITextViewerExtension4;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.TextPresentation;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.widgets.Display;
import org.sveditor.core.db.ISVDBChildItem;
import org.sveditor.core.db.ISVDBChildParent;
import org.sveditor.core.db.SVDBInterfaceDecl;
import org.sveditor.core.db.SVDBItem;
import org.sveditor.core.db.SVDBLocation;
import org.sveditor.core.db.SVDBModIfcClassParam;
import org.sveditor.core.db.SVDBModIfcDecl;
import org.sveditor.core.db.SVDBModuleDecl;
import org.sveditor.core.db.SVDBProgramDecl;
import org.sveditor.core.db.SVDBTypeInfo;
import org.sveditor.core.db.SVDBTypeInfoBuiltinNet;
import org.sveditor.core.db.stmt.SVDBParamPortDecl;
import org.sveditor.core.db.stmt.SVDBVarDeclStmt;
import org.sveditor.ui.SVUiPlugin;
import org.sveditor.ui.pref.SVEditorPrefsConstants;
import org.sveditor.core.scanner.SVCharacter;

/**
 * Highlights references to the ports and parameters of a module, interface
 * or program within the body of that module, using a separate color/style
 * preference for input, output and inout (incl. ref) ports and parameters
 * (incl. localparams).
 *
 * Port scopes are tracked as document positions, so they follow edits
 * until the next re-parse delivers fresh ones.
 */
public class SVPortHighlighter implements ITextPresentationListener {
	private static final String		POSITION_CATEGORY = "org.sveditor.ui.portScopes";

	private static class PortScope {
		final Position				fPos;
		final Map<String, SVEditorColors>	fPorts;

		PortScope(Position pos, Map<String, SVEditorColors> ports) {
			fPos = pos;
			fPorts = ports;
		}
	}

	/** Scope data from the parser, in line terms (1-based, inclusive) */
	private static class LineScope {
		final int					fStartLine;
		final int					fEndLine;
		final Map<String, SVEditorColors>	fPorts;

		LineScope(int start, int end, Map<String, SVEditorColors> ports) {
			fStartLine = start;
			fEndLine = end;
			fPorts = ports;
		}
	}

	private final ISourceViewer			fViewer;
	private IDocument					fDocument;
	private IPositionUpdater			fPositionUpdater;
	private List<PortScope>				fScopes = Collections.emptyList();

	public SVPortHighlighter(ISourceViewer viewer) {
		fViewer = viewer;
	}

	public void install() {
		if (fViewer instanceof ITextViewerExtension4) {
			((ITextViewerExtension4)fViewer).addTextPresentationListener(this);
		}
	}

	public void uninstall() {
		if (fViewer instanceof ITextViewerExtension4) {
			((ITextViewerExtension4)fViewer).removeTextPresentationListener(this);
		}
		releaseDocument();
		fScopes = Collections.emptyList();
	}

	/**
	 * Called with the freshly-parsed (non-preprocessed) file. May be called
	 * from any thread; the document is only touched on the UI thread.
	 */
	public void update(ISVDBChildParent file) {
		final List<LineScope> line_scopes = new ArrayList<LineScope>();
		if (file != null) {
			collectScopes(file, line_scopes);
		}

		Display display = Display.getDefault();
		if (display == null || display.isDisposed()) {
			return;
		}
		display.asyncExec(new Runnable() {
			public void run() {
				applyScopes(line_scopes);
			}
		});
	}

	/**
	 * Re-applies the current scopes, eg after a preference change
	 */
	public void refresh() {
		if (fViewer.getTextWidget() != null && !fViewer.getTextWidget().isDisposed()) {
			fViewer.invalidateTextPresentation();
		}
	}

	private void collectScopes(ISVDBChildParent parent, List<LineScope> scopes) {
		for (ISVDBChildItem ci : parent.getChildren()) {
			if (ci instanceof SVDBModuleDecl || ci instanceof SVDBInterfaceDecl ||
					ci instanceof SVDBProgramDecl) {
				SVDBModIfcDecl decl = (SVDBModIfcDecl)ci;
				Map<String, SVEditorColors> ports = collectPorts(decl);
				long start = decl.getLocation();
				long end = decl.getEndLocation();
				if (!ports.isEmpty() && start != -1) {
					int start_l = SVDBLocation.unpackLineno(start);
					int end_l = (end != -1)?SVDBLocation.unpackLineno(end):Integer.MAX_VALUE;
					scopes.add(new LineScope(start_l, end_l, ports));
				}
			} else if (ci instanceof ISVDBChildParent) {
				// eg modules nested in conditional-compilation regions
				collectScopes((ISVDBChildParent)ci, scopes);
			}
		}
	}

	/**
	 * Port name -> highlight color. ANSI ports carry their direction in the
	 * port list. Non-ANSI ports only have names there (and default to input);
	 * their direction comes from the 'input a;' style body declaration, which
	 * the parser records as a net whose type is the direction keyword.
	 */
	private static Map<String, SVEditorColors> collectPorts(SVDBModIfcDecl decl) {
		Map<String, SVEditorColors> ports = new HashMap<String, SVEditorColors>();

		// Parameters first, so a port of the same name takes precedence:
		// header #(parameter ...) list, then body parameter/localparam decls
		if (decl.getParameters() != null) {
			for (SVDBModIfcClassParam p : decl.getParameters()) {
				if (p.getName() != null && !p.getName().isEmpty()) {
					ports.put(p.getName(), SVEditorColors.PARAMETER);
				}
			}
		}
		for (ISVDBChildItem ci : decl.getChildren()) {
			if (ci instanceof SVDBParamPortDecl) {
				for (ISVDBChildItem pi : ((SVDBParamPortDecl)ci).getChildren()) {
					String name = SVDBItem.getName(pi);
					if (name != null && !name.isEmpty()) {
						ports.put(name, SVEditorColors.PARAMETER);
					}
				}
			}
		}

		for (SVDBParamPortDecl p : decl.getPorts()) {
			SVEditorColors color = dirColor(p.getDir());
			for (ISVDBChildItem pi : p.getChildren()) {
				String name = SVDBItem.getName(pi);
				if (name != null && !name.isEmpty()) {
					ports.put(name, color);
				}
			}
		}

		for (ISVDBChildItem ci : decl.getChildren()) {
			if (!(ci instanceof SVDBVarDeclStmt)) {
				continue;
			}
			SVDBTypeInfo type = ((SVDBVarDeclStmt)ci).getTypeInfo();
			if (!(type instanceof SVDBTypeInfoBuiltinNet)) {
				continue;
			}
			SVEditorColors color = wireTypeColor(((SVDBTypeInfoBuiltinNet)type).getWireType());
			if (color == null) {
				continue;
			}
			for (ISVDBChildItem vi : ((SVDBVarDeclStmt)ci).getChildren()) {
				String name = SVDBItem.getName(vi);
				if (name != null && ports.containsKey(name) &&
						ports.get(name) != SVEditorColors.PARAMETER) {
					ports.put(name, color);
				}
			}
		}
		return ports;
	}

	private static SVEditorColors dirColor(int dir) {
		if ((dir & (SVDBParamPortDecl.Direction_Inout | SVDBParamPortDecl.Direction_Ref)) != 0) {
			return SVEditorColors.PORT_INOUT;
		} else if ((dir & SVDBParamPortDecl.Direction_Output) != 0) {
			return SVEditorColors.PORT_OUTPUT;
		} else {
			return SVEditorColors.PORT_INPUT;
		}
	}

	private static SVEditorColors wireTypeColor(String wire_type) {
		if ("input".equals(wire_type)) {
			return SVEditorColors.PORT_INPUT;
		} else if ("output".equals(wire_type)) {
			return SVEditorColors.PORT_OUTPUT;
		} else if ("inout".equals(wire_type)) {
			return SVEditorColors.PORT_INOUT;
		}
		return null;
	}

	private void applyScopes(List<LineScope> line_scopes) {
		IDocument doc = fViewer.getDocument();
		if (doc == null || fViewer.getTextWidget() == null ||
				fViewer.getTextWidget().isDisposed()) {
			return;
		}
		attachDocument(doc);

		try {
			for (Position p : doc.getPositions(POSITION_CATEGORY)) {
				doc.removePosition(POSITION_CATEGORY, p);
			}
		} catch (BadPositionCategoryException e) {}

		List<PortScope> scopes = new ArrayList<PortScope>();
		int n_lines = doc.getNumberOfLines();
		for (LineScope ls : line_scopes) {
			try {
				int start = doc.getLineOffset(Math.max(ls.fStartLine-1, 0));
				int end = (ls.fEndLine >= n_lines)?doc.getLength():doc.getLineOffset(ls.fEndLine);
				if (end <= start) {
					continue;
				}
				Position pos = new Position(start, end-start);
				doc.addPosition(POSITION_CATEGORY, pos);
				scopes.add(new PortScope(pos, ls.fPorts));
			} catch (BadLocationException | BadPositionCategoryException e) {}
		}
		fScopes = scopes;
		fViewer.invalidateTextPresentation();
	}

	private void attachDocument(IDocument doc) {
		if (doc == fDocument) {
			return;
		}
		releaseDocument();
		fDocument = doc;
		doc.addPositionCategory(POSITION_CATEGORY);
		fPositionUpdater = new DefaultPositionUpdater(POSITION_CATEGORY);
		doc.addPositionUpdater(fPositionUpdater);
	}

	private void releaseDocument() {
		if (fDocument != null) {
			fDocument.removePositionUpdater(fPositionUpdater);
			try {
				fDocument.removePositionCategory(POSITION_CATEGORY);
			} catch (BadPositionCategoryException e) {}
			fDocument = null;
			fPositionUpdater = null;
		}
	}

	public void applyTextPresentation(TextPresentation presentation) {
		List<PortScope> scopes = fScopes;
		IDocument doc = fViewer.getDocument();
		if (scopes.isEmpty() || doc == null || doc != fDocument ||
				!SVUiPlugin.getDefault().getPreferenceStore().getBoolean(
						SVEditorPrefsConstants.P_PORT_HIGHLIGHT_ENABLED)) {
			return;
		}
		IRegion extent = presentation.getExtent();
		int ext_start = extent.getOffset();
		int ext_end = ext_start + extent.getLength();

		for (PortScope scope : scopes) {
			if (scope.fPos.isDeleted()) {
				continue;
			}
			int start = Math.max(scope.fPos.getOffset(), ext_start);
			int end = Math.min(scope.fPos.getOffset()+scope.fPos.getLength(), ext_end);
			if (start >= end) {
				continue;
			}
			try {
				highlightRange(doc, presentation, scope, start, end);
			} catch (BadLocationException e) {}
		}
	}

	private void highlightRange(
			IDocument				doc,
			TextPresentation		presentation,
			PortScope				scope,
			int						start,
			int						end) throws BadLocationException {
		int scope_start = scope.fPos.getOffset();
		int scope_end = scope_start + scope.fPos.getLength();

		// Back up to the start of an identifier that straddles the range start
		while (start > scope_start && SVCharacter.isSVIdentifierPart(doc.getChar(start-1))) {
			start--;
		}

		int i = start;
		while (i < end) {
			char c = doc.getChar(i);
			if (!SVCharacter.isSVIdentifierStart(c)) {
				i++;
				continue;
			}
			int id_start = i;
			while (i < scope_end && SVCharacter.isSVIdentifierPart(doc.getChar(i))) {
				i++;
			}
			String id = doc.get(id_start, i-id_start);
			SVEditorColors color = scope.fPorts.get(id);
			if (color != null && isPortReference(doc, id_start, scope_start)) {
				// Clip to the presentation extent
				IRegion extent = presentation.getExtent();
				int s = Math.max(id_start, extent.getOffset());
				int e = Math.min(i, extent.getOffset()+extent.getLength());
				if (e > s) {
					StyleRange sr = new StyleRange(s, e-s,
							SVEditorColors.getColor(color), null, SVEditorColors.getStyle(color));
					presentation.mergeStyleRange(sr);
				}
			}
		}
	}

	/**
	 * Filters out identifiers that aren't references to this module's ports:
	 * named connections / hierarchical refs (.name), macros (`name), system
	 * tasks ($name), and anything in a comment or string.
	 */
	private boolean isPortReference(IDocument doc, int offset, int scope_start)
			throws BadLocationException {
		int p = offset-1;
		while (p >= scope_start && Character.isWhitespace(doc.getChar(p))) {
			p--;
		}
		if (p >= scope_start) {
			char prev = doc.getChar(p);
			if (prev == '.' || prev == '`' || prev == '$' || prev == '\\') {
				return false;
			}
		}

		if (doc instanceof IDocumentExtension3) {
			try {
				String type = ((IDocumentExtension3)doc).getContentType(
						SVDocumentPartitions.SV_PARTITIONING, offset, false);
				return IDocument.DEFAULT_CONTENT_TYPE.equals(type);
			} catch (BadPartitioningException e) {
				return true;
			}
		}
		return true;
	}
}
