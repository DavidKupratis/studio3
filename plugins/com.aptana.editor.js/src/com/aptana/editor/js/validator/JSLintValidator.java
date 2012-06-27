/**
 * Aptana Studio
 * Copyright (c) 2005-2012 by Appcelerator, Inc. All Rights Reserved.
 * Licensed under the terms of the GNU Public License (GPL) v3 (with exceptions).
 * Please see the license.html included with this distribution for details.
 * Any modifications to this file must keep this entire header intact.
 */
package com.aptana.editor.js.validator;

import java.io.IOException;
import java.net.URL;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextAction;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.ErrorReporter;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import com.aptana.core.IFilter;
import com.aptana.core.build.AbstractBuildParticipant;
import com.aptana.core.build.IProblem;
import com.aptana.core.logging.IdeLog;
import com.aptana.core.util.ArrayUtil;
import com.aptana.core.util.CollectionsUtil;
import com.aptana.core.util.StreamUtil;
import com.aptana.editor.js.IJSConstants;
import com.aptana.editor.js.JSPlugin;
import com.aptana.index.core.build.BuildContext;

/**
 * Runs the code against JSLint inside Rhino, then parses out the reported errors/warnings.
 * 
 * @author cwilliams
 */
public class JSLintValidator extends AbstractBuildParticipant
{
	/**
	 * The unique ID of this validator/build participant.
	 */
	public static final String ID = "com.aptana.editor.js.validator.JSLintValidator"; //$NON-NLS-1$

	private static final String JSLINT_FILENAME = "fulljslint.js"; //$NON-NLS-1$
	private static JSLint JS_LINT_SCRIPT;

	private static ContextFactory contextFactory = new ContextFactory();

	private static Map<String, Object> DEFAULT_OPTION = new HashMap<String, Object>();
	static
	{
		// Set default aptana options
		DEFAULT_OPTION.put("laxLineEnd", true); //$NON-NLS-1$
		DEFAULT_OPTION.put("undef", true); //$NON-NLS-1$
		DEFAULT_OPTION.put("browser", true); //$NON-NLS-1$
		DEFAULT_OPTION.put("jscript", true); //$NON-NLS-1$
		DEFAULT_OPTION.put("debug", true); //$NON-NLS-1$
		DEFAULT_OPTION.put("maxerr", 100000); //$NON-NLS-1$
		DEFAULT_OPTION.put("white", true); //$NON-NLS-1$
		DEFAULT_OPTION.put(
				"predef", new NativeArray(new String[] { "Ti", "Titanium", "alert", "require", "exports", "native", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
						"implements" })); //$NON-NLS-1$
	}

	private Map<String, Object> options;

	public JSLintValidator()
	{
		super();
	}

	public void buildFile(BuildContext context, IProgressMonitor monitor)
	{
		if (context == null)
		{
			return;
		}

		intializeOptions();
		List<IProblem> problems = Collections.emptyList();
		String sourcePath = context.getURI().toString();
		try
		{
			problems = parseWithLint(context.getContents(), sourcePath);
		}
		catch (Exception e)
		{
			IdeLog.logError(JSPlugin.getDefault(),
					MessageFormat.format("Failed to parse {0} with JSLint", sourcePath), e); //$NON-NLS-1$
		}

		this.options = null;
		context.putProblems(IJSConstants.JSLINT_PROBLEM_MARKER_TYPE, problems);
	}

	public void deleteFile(BuildContext context, IProgressMonitor monitor)
	{
		if (context == null)
		{
			return;
		}

		context.removeProblems(IJSConstants.JSLINT_PROBLEM_MARKER_TYPE);
	}

	private List<IProblem> parseWithLint(String source, String path)
	{
		JSLint script = getJSLintScript();
		if (script == null)
		{
			return Collections.emptyList();
		}

		script.runLint(source, this.options);

		List<IProblem> collected = script.getProblems(source, path);
		final List<String> filters = getFilters();
		return CollectionsUtil.filter(collected, new IFilter<IProblem>()
		{

			public boolean include(IProblem item)
			{
				return !isIgnored(item.getMessage(), filters);
			}
		});
	}

	/**
	 * Lazily grab the JSLint script.
	 * 
	 * @return
	 */
	private synchronized JSLint getJSLintScript()
	{
		if (JS_LINT_SCRIPT == null)
		{
			URL url = FileLocator.find(JSPlugin.getDefault().getBundle(), Path.fromPortableString(JSLINT_FILENAME),
					null);
			if (url != null)
			{
				try
				{
					String source = StreamUtil.readContent(url.openStream());
					if (source != null)
					{
						JS_LINT_SCRIPT = getJSLintScript(source);
					}
				}
				catch (IOException e)
				{
					IdeLog.logError(JSPlugin.getDefault(), Messages.JSLintValidator_ERR_FailToGetJSLint, e);
				}
			}
		}
		return JS_LINT_SCRIPT;
	}

	/**
	 * Compile JSLint file into {@link Script} object.
	 * 
	 * @param source
	 * @return
	 */
	private JSLint getJSLintScript(String source)
	{
		try
		{
			Context cx = contextFactory.enterContext();
			ScriptableObject scope = cx.initStandardObjects();
			cx.evaluateString(scope, source, JSLINT_FILENAME, 1, null);
			return new JSLint(contextFactory, scope);
		}
		finally
		{
			Context.exit();
		}
	}

	public void setOption(String propertyName, Object value)
	{
		intializeOptions();
		this.options.put(propertyName, value);
	}

	private void intializeOptions()
	{
		if (this.options == null)
		{
			this.options = new HashMap<String, Object>();
			this.options.putAll(DEFAULT_OPTION);
		}
	}

	class JSLint
	{
		private ContextFactory contextFactory;
		private ScriptableObject scope;

		JSLint(ContextFactory contextFactory, ScriptableObject scope)
		{
			this.contextFactory = contextFactory;
			this.scope = scope;
			this.scope.sealObject();
		}

		public List<IProblem> getProblems(final String source, final String path)
		{
			final List<IProblem> items = new ArrayList<IProblem>();

			contextFactory.call(new ContextAction()
			{
				public Object run(Context cx)
				{
					Function lintFunc = (Function) scope.get("JSLINT", scope); //$NON-NLS-1$

					Object errorObject = lintFunc.get("errors", scope); //$NON-NLS-1$
					if (!(errorObject instanceof NativeArray))
					{
						return null;
					}

					NativeArray errorArray = (NativeArray) errorObject;
					Object[] ids = errorArray.getIds();
					if (ArrayUtil.isEmpty(ids))
					{
						return null;
					}

					boolean lastIsError = false;
					NativeObject last = (NativeObject) errorArray.get(Integer.parseInt(ids[ids.length - 1].toString()),
							scope);
					if (last == null)
					{
						lastIsError = true;
					}

					IDocument doc = null; // Lazily init document object to query about lines/offsets
					for (int i = 0; i < ids.length; ++i)
					{
						// Grab the warning/error
						NativeObject object = (NativeObject) errorArray.get(Integer.parseInt(ids[i].toString()), scope);
						if (object == null)
						{
							continue;
						}

						// Grab the line of the error. Skip if we already recorded an error on this line (why?)
						int line = (int) Double.parseDouble(object.get("line", scope).toString()); //$NON-NLS-1$

						// Grab the details of the error. If user has set up filters to ignore it, move on
						String reason = object.get("reason", scope).toString().trim(); //$NON-NLS-1$

						// lazy init of document to query for offsets/line info
						if (doc == null)
						{
							doc = new Document(source);
						}

						// Translate the column reported into the absolute offset from start of doc
						int character = (int) Double.parseDouble(object.get("character", scope).toString()); //$NON-NLS-1$
						try
						{
							// JSLint reports the offset as column on the given line, and counts tab characters as 4
							// columns
							// We account for that by adding the offset of the line start, and reducing the column count
							// on
							// tabs
							IRegion lineInfo = doc.getLineInformation(line - 1);
							int realOffset = lineInfo.getOffset();
							String rawLine = doc.get(realOffset, lineInfo.getLength());
							int actual = character - 1;
							for (int x = 0; x < actual; x++)
							{
								char c = rawLine.charAt(x);
								if (c == '\t')
								{
									actual -= 3;
								}
								realOffset++;
							}
							character = realOffset;
						}
						catch (BadLocationException e)
						{
							// ignore
						}

						// Now record the error
						if (i == ids.length - 2 && lastIsError)
						{
							// If this starts with "Stopping", convert the last warning to an error and skip this.
							if (reason.startsWith("Stopping")) //$NON-NLS-1$
							{
								IProblem lastWarning = items.remove(items.size() - 1);
								items.add(createError(lastWarning.getMessage(), lastWarning.getLineNumber(),
										lastWarning.getOffset(), 1, path));
							}
							else
							{
								items.add(createError(reason, line, character, 1, path));
							}
						}
						else
						{
							items.add(createWarning(reason, line, character, 1, path));
						}
					}
					return null;
				}
			});
			return items;
		}

		void runLint(final String source, final Map<String, Object> options)
		{
			contextFactory.call(new ContextAction()
			{
				public Object run(Context cx)
				{
					Object[] args = new Object[] { source, optionsAsJavaScriptObject(options) };
					Function lintFunc = (Function) scope.get("JSLINT", scope); //$NON-NLS-1$
					// PC: we ignore the result, because i have found that with some versions, there might
					// be errors but this function returned true (false == errors)
					lintFunc.call(cx, scope, scope, args);
					return null;
				}
			});
		}

		private Scriptable optionsAsJavaScriptObject(final Map<String, Object> options)
		{
			return (Scriptable) contextFactory.call(new ContextAction()
			{
				public Object run(Context cx)
				{
					Scriptable opts = cx.newObject(scope);
					for (Map.Entry<String, Object> entry : options.entrySet())
					{
						String key = entry.getKey();
						Object value = javaToJS(entry.getValue(), opts);
						opts.put(key, opts, value);
					}
					return opts;
				}
			});
		}

		Object javaToJS(Object o, Scriptable scope)
		{
			Class<?> cls = o.getClass();
			if (cls.isArray())
			{
				return new NativeArray((Object[]) o);
			}

			return Context.javaToJS(o, scope);
		}
	}

	class DefaultErrorReporter implements ErrorReporter
	{

		private List<IProblem> items = new ArrayList<IProblem>();

		public DefaultErrorReporter()
		{
			items = new ArrayList<IProblem>();
		}

		public void error(String message, String sourceURI, int line, String lineText, int lineOffset)
		{
			items.add(createError(message, line, lineOffset, 1, sourceURI));
		}

		public void warning(String message, String sourceURI, int line, String lineText, int lineOffset)
		{
			items.add(createWarning(message, line, lineOffset, 1, sourceURI));
		}

		public EvaluatorException runtimeError(String message, String sourceURI, int line, String lineText,
				int lineOffset)
		{
			return new EvaluatorException(message, sourceURI, line, lineText, lineOffset);
		}

		public List<IProblem> getItems()
		{
			return Collections.unmodifiableList(items);
		}
	}
}
