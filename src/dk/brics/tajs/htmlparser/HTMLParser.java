/*
 * Copyright 2009-2016 Aarhus University
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dk.brics.tajs.htmlparser;

import dk.brics.tajs.flowgraph.JavaScriptSource;
import dk.brics.tajs.util.AnalysisLimitationException;
import dk.brics.tajs.util.Loader;
import net.htmlparser.jericho.Attribute;
import net.htmlparser.jericho.Attributes;
import net.htmlparser.jericho.Element;
import net.htmlparser.jericho.RowColumnVector;
import net.htmlparser.jericho.Source;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static dk.brics.tajs.util.Collections.newList;
import static dk.brics.tajs.util.Collections.newSet;

/**
 * HTML parser based on Jericho.
 */
public class HTMLParser {

    private Source doc;

    private List<JavaScriptSource> code;

    /**
     * Parses the given HTML file.
     */
    public HTMLParser(Path htmlFile) throws IOException {
        Path root = htmlFile.getParent(); // XXX assumes that the directory of the htmlFile is the root for non-relative paths in the htmlFile

        Set<String> standardJavaScriptScriptTypeNames = newSet(Arrays.asList("text/javascript", "text/ecmascript", "application/javascript", "application/ecmascript"));
        Set<String> allJavaScriptScriptTypeNames = newSet();
        allJavaScriptScriptTypeNames.addAll(standardJavaScriptScriptTypeNames);
        // add some extra names to cater for a common typo
        allJavaScriptScriptTypeNames.addAll(Arrays.asList("javascript", "ecmascript", ""));

        doc = new Source(htmlFile.toFile());
        code = newList();
        for (Element e : doc.getAllElements()) {
            String name = e.getName();
            if (name.equals("script") && (e.getAttributeValue("type") == null || allJavaScriptScriptTypeNames.contains(e.getAttributeValue("type")))) {
                String src = e.getAttributeValue("src");
                if (src != null) {
                    // external script
                    code.add(JavaScriptSource.makeFileCode(src, Loader.getString(resolveSrcReference(root, htmlFile, src).toString(), "UTF-8")));
                } else {
                    // embedded script
                    RowColumnVector pos = doc.getRowColumnVector(e.getStartTag().getEnd());
                    code.add(JavaScriptSource.makeEmbeddedCode(htmlFile.toString(), e.getContent().toString(), pos.getRow() - 1, pos.getColumn() - 1));
                }
            } else if (name.equals("a") || name.equals("form")) {
                Attributes as = e.getAttributes();
                if (as != null) {
                    Attribute a = name.equals("a") ? as.get("href") : as.get("action");
                    if (a != null) {
                        String val = a.getValue();
                        final String JAVASCRIPT = "javascript:";
                        if (val != null) {
                            if (val.length() > JAVASCRIPT.length() && val.substring(0, JAVASCRIPT.length()).equalsIgnoreCase(JAVASCRIPT)) {
                                // embedded 'javascript:' event handler
                                String js = val.substring(JAVASCRIPT.length());
                                RowColumnVector pos = doc.getRowColumnVector(a.getValueSegment().getBegin() + JAVASCRIPT.length());
                                code.add(JavaScriptSource.makeEventHandlerCode(name.equals("a") ? "click" : "submit", htmlFile.toString(), js, pos.getRow() - 1, pos.getColumn() - 1));
                            }
                        }
                    }
                }
            }
            Attributes as = e.getAttributes();
            if (as != null) {
                for (Attribute a : as) {
                    String aname = a.getKey();
                    final String ON = "on";
                    if (aname.startsWith(ON)) { // may include too many attributes in case of bad HTML...
                        String val = a.getValue();
                        if (val != null) {
                            // embedded 'on...' event handler
                            RowColumnVector pos = doc.getRowColumnVector(a.getValueSegment().getBegin());
                            code.add(JavaScriptSource.makeEventHandlerCode(aname.substring(ON.length()), htmlFile.toString(), val, pos.getRow() - 1, pos.getColumn() - 1));
                        }
                    }
                }
            }
        }
    }

    /**
     * Returns the HTML.
     */
    public Source getHTML() {
        return doc;
    }

    /**
     * Returns all JavaScript code in the document, both from both embedded/external 'script' elements and event handlers.
     * The order of elements is that of a DFS traversal of the HTML document.
     */
    public List<JavaScriptSource> getJavaScript() {
        return code;
    }

    /**
     * Resolves a reference to a file.
     *
     * The reference can be resolved relatively to a root or the file containing the reference.
     */
    private Path resolveSrcReference(Path root, Path fileWithReferenceIn, String reference) {
        try {
            URI uri = new URI(reference);
            String scheme = uri.getScheme();
            if (scheme != null) {
                // TODO support http, https, file, ...
                throw new AnalysisLimitationException("Explicit schemes are not supported. Bad reference: " + reference);
            }
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
        if (reference.startsWith("//")) {
            throw new AnalysisLimitationException("Implicit schemes are not supported. Bad reference: " + reference);
        }

        // TODO normalize paths to avoid treating `dir/../test.js` and `test.js` as different files!
        Path parent;
        if (reference.startsWith(".")) {
            parent = fileWithReferenceIn.getParent();
        } else {
            parent = root;
        }
        return parent == null? Paths.get(reference): parent.resolve(reference);
    }
}
