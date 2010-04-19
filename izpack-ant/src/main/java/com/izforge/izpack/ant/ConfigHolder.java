/*
/*
 * IzPack - Copyright 2001-2008 Julien Ponge, All Rights Reserved.
 * 
 * http://izpack.org/
 * http://izpack.codehaus.org/
 * 
 * Copyright 2002 Marcus Wolschon
 * Copyright 2002 Jan Blok
 * Copyright 2004 Klaus Bartz
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

package com.izforge.izpack.ant;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Project;

import java.util.*;

/**
 * A nested element holder for the installation configuration document content.
 * The installation document must be passed in using a CDATA element.
 *
 * @author Scott Stark
 * @version $Revision$
 */
public class ConfigHolder
{
    /**
     * The parent element project
     */
    private Project project;

    /**
     * The config element body text with ${x} property references replaced
     */
    private String installText;

    /**
     * Taken from the ant org.apache.tools.ant.PropertyHelper and '$' replaced
     * with '@' to deal with @{x} style property references.
     * <p/>
     * Parses a string containing @{xxx} style property references
     * into two lists. The first list is a collection of text fragments, while
     * the other is a set of string property names. null entries in the
     * first list indicate a property reference from the second list.
     * <p/>
     * It can be overridden with a more efficient or customized version.
     *
     * @param value        Text to parse. Must not be null.
     * @param fragments    List to add text fragments to. Must not be null.
     * @param propertyRefs List to add property names to. Must not be null.
     * @throws BuildException if the string contains an opening @{ without a
     *                        closing }
     */
    static void parseCompileProperties(String value, List<String> fragments, List<String> propertyRefs)
            throws BuildException
    {
        int prev = 0;
        int pos;
        // search for the next instance of $ from the 'prev' position
        while ((pos = value.indexOf("@", prev)) >= 0)
        {

            // if there was any text before this, add it as a fragment
            // TODO, this check could be modified to go if pos>prev;
            // seems like this current version could stick empty strings
            // into the list
            if (pos > 0)
            {
                fragments.add(value.substring(prev, pos));
            }
            // if we are at the end of the string, we tack on a $
            // then move past it
            if (pos == (value.length() - 1))
            {
                fragments.add("@");
                prev = pos + 1;
            }
            else if (value.charAt(pos + 1) != '{')
            {
                // peek ahead to see if the next char is a property or not
                // not a property: insert the char as a literal
                /*
                 * fragments.addElement(value.substring(pos + 1, pos + 2)); prev = pos + 2;
                 */
                if (value.charAt(pos + 1) == '@')
                {
                    // backwards compatibility two $ map to one mode
                    fragments.add("@");
                    prev = pos + 2;
                }
                else
                {
                    // new behaviour: $X maps to $X for all values of X!='$'
                    fragments.add(value.substring(pos, pos + 2));
                    prev = pos + 2;
                }

            }
            else
            {
                // property found, extract its name or bail on a typo
                int endName = value.indexOf('}', pos);
                if (endName < 0)
                {
                    throw new BuildException("Syntax error in property: " + value);
                }
                String propertyName = value.substring(pos + 2, endName);
                fragments.add(null);
                propertyRefs.add(propertyName);
                prev = endName + 1;
            }
        }
        // no more @ signs found
        // if there is any tail to the file, append it
        if (prev < value.length())
        {
            fragments.add(value.substring(prev));
        }
    }

    ConfigHolder(Project project)
    {
        this.project = project;
    }

    /**
     * Called by ant to set the config element content. The content is scanned
     * for @{x} style property references and replaced with the x project
     * property.
     *
     * @param rawText - the raw config element body text.
     */
    public void addText(String rawText)
    {
        // Locate the @{x} references
        List<String> fragments = new ArrayList<String>();
        List<String> propertyRefs = new ArrayList<String>();
        parseCompileProperties(rawText, fragments, propertyRefs);

        // Replace the references with the project property value
        StringBuffer sb = new StringBuffer();
        Enumeration<String> fragmentsEnumeration = Collections.enumeration(fragments);
        Enumeration<String> propertyRefsEnumeration = Collections.enumeration(propertyRefs);

        while (fragmentsEnumeration.hasMoreElements())
        {
            String fragment = fragmentsEnumeration.nextElement();
            if (fragment == null)
            {
                String propertyName = propertyRefsEnumeration.nextElement();

                // try to get it from the project
                String replacement = project.getProperty(propertyName);

                if (replacement == null)
                {
                    project.log("Property @{" + propertyName + "} has not been set",
                            Project.MSG_VERBOSE);
                }
                if (replacement != null)
                {
                    fragment = replacement;
                }
                else
                {
                    fragment = "@{" + propertyName + "}";
                }
            }
            sb.append(fragment);
        }

        installText = sb.toString();
    }

    /**
     * Get the config element body text with @{x} property references replaced
     *
     * @return the processed config element body text.
     */
    public String getText()
    {
        return installText;
    }

}
