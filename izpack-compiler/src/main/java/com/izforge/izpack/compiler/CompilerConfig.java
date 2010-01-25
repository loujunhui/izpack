/*
 * $Id$
 * IzPack - Copyright 2001-2008 Julien Ponge, All Rights Reserved.
 *
 * http://izpack.org/
 * http://izpack.codehaus.org/
 *
 * Copyright 2001 Johannes Lehtinen
 * Copyright 2002 Paul Wilkinson
 * Copyright 2004 Gaganis Giorgos
 * Copyright 2007 Syed Khadeer / Hans Aikema
 *
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

package com.izforge.izpack.compiler;

import com.izforge.izpack.api.adaptator.IXMLElement;
import com.izforge.izpack.api.adaptator.IXMLParser;
import com.izforge.izpack.api.adaptator.IXMLWriter;
import com.izforge.izpack.api.adaptator.impl.XMLParser;
import com.izforge.izpack.api.adaptator.impl.XMLWriter;
import com.izforge.izpack.api.data.*;
import com.izforge.izpack.api.exception.CompilerException;
import com.izforge.izpack.api.installer.DataValidator;
import com.izforge.izpack.api.rules.Condition;
import com.izforge.izpack.api.substitutor.SubstitutionType;
import com.izforge.izpack.api.substitutor.VariableSubstitutor;
import com.izforge.izpack.compiler.data.CompilerData;
import com.izforge.izpack.compiler.data.PropertyManager;
import com.izforge.izpack.compiler.helper.AssertionHelper;
import com.izforge.izpack.compiler.helper.CompilerHelper;
import com.izforge.izpack.compiler.helper.XmlCompilerHelper;
import com.izforge.izpack.compiler.listener.CompilerListener;
import com.izforge.izpack.compiler.packager.IPackager;
import com.izforge.izpack.core.rules.RulesEngineImpl;
import com.izforge.izpack.data.*;
import com.izforge.izpack.data.PanelAction.ActionStage;
import com.izforge.izpack.util.Debug;
import com.izforge.izpack.util.IoHelper;
import com.izforge.izpack.util.OsConstraint;
import org.apache.tools.ant.DirectoryScanner;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;


/**
 * A parser for the installer xml configuration. This parses a document conforming to the
 * installation.dtd and populates a Compiler instance to perform the install compilation.
 *
 * @author Scott Stark
 * @version $Revision$
 */
public class CompilerConfig extends Thread {

    /**
     * Constant for checking attributes.
     */
    private static boolean YES = true;

    /**
     * Constant for checking attributes.
     */
    private static boolean NO = false;

    /**
     * The installer packager compiler
     */
    private Compiler compiler;

    /**
     * Installer data
     */
    private CompilerData compilerData;

    /**
     * Compiler helper
     */
    private CompilerHelper compilerHelper;

    /**
     * List of CompilerListeners which should be called at packaging
     */
    protected List<CompilerListener> compilerListeners = new ArrayList<CompilerListener>();

    /**
     * A list of packsLang-files that were defined by the user in the resource-section The key of
     * this map is an packsLang-file identifier, e.g. <code>packsLang.xml_eng</code>, the values
     * are lists of {@link URL} pointing to the concrete packsLang-files.
     *
     * @see #mergePacksLangFiles()
     */
    private HashMap<String, List<URL>> packsLangUrlMap = new HashMap<String, List<URL>>();
    private String unpackerClassname = "com.izforge.izpack.installer.unpacker.Unpacker";
    private String packagerClassname = "com.izforge.izpack.compiler.packager.impl.Packager";
    private VariableSubstitutor variableSubstitutor;
    private XmlCompilerHelper xmlCompilerHelper;
    private PropertyManager propertyManager;

    /**
     * Constructor
     *
     * @param compilerData Object containing all informations found in command line
     */
    public CompilerConfig(CompilerData compilerData, VariableSubstitutor variableSubstitutor, Compiler compiler, CompilerHelper compilerHelper, XmlCompilerHelper xmlCompilerHelper, PropertyManager propertyManager) {
        this.compilerData = compilerData;
        this.variableSubstitutor = variableSubstitutor;
        this.compiler = compiler;
        this.compilerHelper = compilerHelper;
        this.xmlCompilerHelper = xmlCompilerHelper;
        this.propertyManager = propertyManager;
    }

    /**
     * Add a name value pair to the project property set. It is <i>not</i> replaced it is already
     * in the set of properties.
     *
     * @param name  the name of the property
     * @param value the value to set
     * @return true if the property was not already set
     */
    public boolean addProperty(String name, String value) {
        return propertyManager.addProperty(name, value);
    }

    /**
     * Access the install compiler
     *
     * @return the install compiler
     */
    public Compiler getCompiler() {
        return compiler;
    }

    /**
     * The run() method.
     */
    public void run() {
        try {
            executeCompiler();
        }
        catch (CompilerException ce) {
            System.out.println(ce.getMessage() + "\n");
        }
        catch (Exception e) {
            if (Debug.stackTracing()) {
                e.printStackTrace();
            } else {
                System.out.println("ERROR: " + e.getMessage());
            }
        }
    }

    /**
     * Compiles the installation.
     *
     * @throws Exception Description of the Exception
     */
    public void executeCompiler() throws Exception {
        // normalize and test: TODO: may allow failure if we require write
        // access
        File base = new File(compilerData.getBasedir()).getAbsoluteFile();
        if (!base.canRead() || !base.isDirectory()) {
            throw new CompilerException(
                    "Invalid base directory: " + base);
        }

        // add izpack built in property
        propertyManager.setProperty("basedir", base.toString());

        // We get the XML data tree
        IXMLElement data = getXMLTree();
        // loads the specified packager
        loadPackagingInformation(data);

        // Listeners to various events
        addCustomListeners(data);

        // Read the properties and perform replacement on the rest of the tree
        substituteProperties(data);

        // We add all the information
        addVariables(data);
        addDynamicVariables(data);
        addConditions(data);
        addInfo(data);
        addGUIPrefs(data);
        addLangpacks(data);
        addResources(data);
        addNativeLibraries(data);
        addJars(data);
        addPanels(data);
        addPacks(data);
        addInstallerRequirement(data);

        // merge multiple packlang.xml files
        mergePacksLangFiles();

        // We ask the packager to create the installer
        compiler.createInstaller();
    }

    private void addInstallerRequirement(IXMLElement data) throws CompilerException {
        notifyCompilerListener("addInstallerRequirement", CompilerListener.BEGIN, data);
        IXMLElement root = data.getFirstChildNamed("installerrequirements");
        List<InstallerRequirement> installerrequirements = new ArrayList<InstallerRequirement>();

        if (root != null) {
            Vector<IXMLElement> installerrequirementsels = root
                    .getChildrenNamed("installerrequirement");
            for (IXMLElement installerrequirement : installerrequirementsels) {
                InstallerRequirement basicInstallerCondition = new InstallerRequirement();
                String condition = installerrequirement.getAttribute("condition");
                basicInstallerCondition.setCondition(condition);
                String message = installerrequirement.getAttribute("message");
                basicInstallerCondition.setMessage(message);
                installerrequirements.add(basicInstallerCondition);
            }
        }
        compiler.addInstallerRequirement(installerrequirements);
        notifyCompilerListener("addInstallerRequirement", CompilerListener.END, data);
    }

    private void loadPackagingInformation(IXMLElement data) throws CompilerException {
        notifyCompilerListener("loadPackager", CompilerListener.BEGIN, data);
        // Initialisation
        IXMLElement root = data.getFirstChildNamed("packaging");
        IXMLElement packager = null;
        if (root != null) {
            packager = root.getFirstChildNamed("packager");

            if (packager != null) {
                packagerClassname = xmlCompilerHelper.requireAttribute(packager, "class", compilerData.getInstallFile());
            }

            IXMLElement unpacker = root.getFirstChildNamed("unpacker");

            if (unpacker != null) {
                unpackerClassname = xmlCompilerHelper.requireAttribute(unpacker, "class", compilerData.getInstallFile());
            }
        }
        if (packager != null) {
            IXMLElement options = packager.getFirstChildNamed("options");
            if (options != null) {
                compiler.getPackager().addConfigurationInformation(options);
            }
        }
        propertyManager.addProperty("UNPACKER_CLASS", unpackerClassname);
        notifyCompilerListener("loadPackager", CompilerListener.END, data);
    }

    public boolean wasSuccessful() {
        return compiler.wasSuccessful();
    }

    /**
     * Returns the GUIPrefs.
     *
     * @param data The XML data.
     * @throws CompilerException Description of the Exception
     */
    protected void addGUIPrefs(IXMLElement data) throws CompilerException {
        notifyCompilerListener("addGUIPrefs", CompilerListener.BEGIN, data);
        // We get the IXMLElement & the attributes
        IXMLElement gp = data.getFirstChildNamed("guiprefs");
        GUIPrefs prefs = new GUIPrefs();
        if (gp != null) {
            prefs.resizable = xmlCompilerHelper.requireYesNoAttribute(gp, "resizable", compilerData.getInstallFile());
            prefs.width = xmlCompilerHelper.requireIntAttribute(gp, "width", compilerData.getInstallFile());
            prefs.height = xmlCompilerHelper.requireIntAttribute(gp, "height", compilerData.getInstallFile());

            // Look and feel mappings
            for (IXMLElement lafNode : gp.getChildrenNamed("laf")) {
                String lafName = xmlCompilerHelper.requireAttribute(lafNode, "name", compilerData.getInstallFile());
                xmlCompilerHelper.requireChildNamed(lafNode, "os");

                for (IXMLElement osNode : lafNode.getChildrenNamed("os")) {
                    String osName = xmlCompilerHelper.requireAttribute(osNode, "family", compilerData.getInstallFile());
                    prefs.lookAndFeelMapping.put(osName, lafName);
                }

                Map<String, String> params = new TreeMap<String, String>();
                for (IXMLElement parameterNode : lafNode.getChildrenNamed("param")) {
                    String name = xmlCompilerHelper.requireAttribute(parameterNode, "name", compilerData.getInstallFile());
                    String value = xmlCompilerHelper.requireAttribute(parameterNode, "value", compilerData.getInstallFile());
                    params.put(name, value);
                }
                prefs.lookAndFeelParams.put(lafName, params);
            }
            // Load modifier
            for (IXMLElement ixmlElement : gp.getChildrenNamed("modifier")) {
                String key = xmlCompilerHelper.requireAttribute(ixmlElement, "key", compilerData.getInstallFile());
                String value = xmlCompilerHelper.requireAttribute(ixmlElement, "value", compilerData.getInstallFile());
                prefs.modifier.put(key, value);

            }
            // make sure jar contents of each are available in installer
            // map is easier to read/modify than if tree
            HashMap<String, String> lafMap = new HashMap<String, String>();
            lafMap.put("liquid", "liquidlnf.jar");
            lafMap.put("kunststoff", "kunststoff.jar");
            lafMap.put("metouia", "metouia.jar");
            lafMap.put("looks", "looks.jar");
            lafMap.put("substance", "substance.jar");
            lafMap.put("nimbus", "nimbus.jar");

            // is this really what we want? a double loop? needed, since above,
            // it's
            // the /last/ lnf for an os which is used, so can't add during
            // initial
            // loop
            for (String s : prefs.lookAndFeelMapping.keySet()) {
                String lafName = prefs.lookAndFeelMapping.get(s);
                String lafJarName = lafMap.get(lafName);
                if (lafJarName == null) {
                    AssertionHelper.parseError(gp, "Unrecognized Look and Feel: " + lafName, compilerData.getInstallFile());
                }

                URL lafJarURL = findIzPackResource("lib/" + lafJarName, "Look and Feel Jar file",
                        gp);
                compiler.addJarContent(lafJarURL);
            }
        }
        compiler.setGUIPrefs(prefs);
        notifyCompilerListener("addGUIPrefs", CompilerListener.END, data);
    }

    /**
     * Add project specific external jar files to the installer.
     *
     * @param data The XML data.
     */
    protected void addJars(IXMLElement data) throws Exception {
        notifyCompilerListener("addJars", CompilerListener.BEGIN, data);
        for (IXMLElement ixmlElement : data.getChildrenNamed("jar")) {
            String src = xmlCompilerHelper.requireAttribute(ixmlElement, "src", compilerData.getInstallFile());
            URL url = findProjectResource(src, "Jar file", ixmlElement);
            compiler.addJarContent(url);
            // Additionals for mark a jar file also used in the uninstaller.
            // The contained files will be copied from the installer into the
            // uninstaller if needed.
            // Therefore the contained files of the jar should be in the
            // installer also
            // they are used only from the uninstaller. This is the reason why
            // the stage
            // wiil be only observed for the uninstaller.
            String stage = ixmlElement.getAttribute("stage");
            if (stage != null
                    && ("both".equalsIgnoreCase(stage) || "uninstall".equalsIgnoreCase(stage))) {
                CustomData ca = new CustomData(null, compilerHelper.getContainedFilePaths(url), null,
                        CustomData.UNINSTALLER_JAR);
                compiler.addCustomJar(ca, url);
            }
        }
        notifyCompilerListener("addJars", CompilerListener.END, data);
    }

    /**
     * Add native libraries to the installer.
     *
     * @param data The XML data.
     */
    protected void addNativeLibraries(IXMLElement data) throws Exception {
        boolean needAddOns = false;
        notifyCompilerListener("addNativeLibraries", CompilerListener.BEGIN, data);
        for (IXMLElement ixmlElement : data.getChildrenNamed("native")) {
            String type = xmlCompilerHelper.requireAttribute(ixmlElement, "type", compilerData.getInstallFile());
            String name = xmlCompilerHelper.requireAttribute(ixmlElement, "name", compilerData.getInstallFile());
            String path = ixmlElement.getAttribute("src");
            if (path == null) {
                path = "bin/native/" + type + "/" + name;
            }
            URL url = findIzPackResource(path, "Native Library", ixmlElement);
            compiler.addNativeLibrary(name, url);
            // Additionals for mark a native lib also used in the uninstaller
            // The lib will be copied from the installer into the uninstaller if
            // needed.
            // Therefore the lib should be in the installer also it is used only
            // from
            // the uninstaller. This is the reason why the stage wiil be only
            // observed
            // for the uninstaller.
            String stage = ixmlElement.getAttribute("stage");
            List<OsConstraint> constraints = OsConstraint.getOsList(ixmlElement);
            if (stage != null
                    && ("both".equalsIgnoreCase(stage) || "uninstall".equalsIgnoreCase(stage))) {
                ArrayList<String> al = new ArrayList<String>();
                al.add(name);
                CustomData cad = new CustomData(null, al, constraints, CustomData.UNINSTALLER_LIB);
                compiler.addNativeUninstallerLibrary(cad);
                needAddOns = true;
            }

        }
        if (needAddOns) {
            // Add the uninstaller extensions as a resource if specified
            IXMLElement root = xmlCompilerHelper.requireChildNamed(data, "info");
            IXMLElement uninstallInfo = root.getFirstChildNamed("uninstaller");
            if (xmlCompilerHelper.validateYesNoAttribute(uninstallInfo, "write", YES, compilerData.getInstallFile())) {
                URL url = findIzPackResource(propertyManager.getProperty("uninstaller-ext"), "Uninstaller extensions",
                        root);
                compiler.addResource("IzPack.uninstaller-ext", url);
            }

        }
        notifyCompilerListener("addNativeLibraries", CompilerListener.END, data);
    }

    /**
     * Add packs and their contents to the installer.
     *
     * @param data The XML data.
     */
    protected void addPacks(IXMLElement data) throws CompilerException {
        notifyCompilerListener("addPacks", CompilerListener.BEGIN, data);

        // the actual adding is delegated to addPacksSingle to enable recursive
        // parsing of refpack package definitions
        addPacksSingle(data);

        compiler.checkDependencies();
        compiler.checkExcludes();

        notifyCompilerListener("addPacks", CompilerListener.END, data);
    }

    /**
     * Add packs and their contents to the installer without checking the dependencies and includes.
     * <p/> Helper method to recursively add more packs from refpack XML packs definitions
     *
     * @param data The XML data
     * @throws CompilerException
     */
    private void addPacksSingle(IXMLElement data) throws CompilerException {
        notifyCompilerListener("addPacksSingle", CompilerListener.BEGIN, data);
        // Initialisation
        IXMLElement root = xmlCompilerHelper.requireChildNamed(data, "packs");

        // at least one pack is required
        Vector<IXMLElement> packElements = root.getChildrenNamed("pack");
        Vector<IXMLElement> refPackElements = root.getChildrenNamed("refpack");
        Vector<IXMLElement> refPackSets = root.getChildrenNamed("refpackset");
        if (packElements.isEmpty() && refPackElements.isEmpty() && refPackSets.isEmpty()) {
            AssertionHelper.parseError(root, "<packs> requires a <pack>, <refpack> or <refpackset>", compilerData.getInstallFile());
        }

        File baseDir = new File(compilerData.getBasedir());

        for (IXMLElement packElement : packElements) {

            // Trivial initialisations
            String name = xmlCompilerHelper.requireAttribute(packElement, "name", compilerData.getInstallFile());
            String id = packElement.getAttribute("id");
            String packImgId = packElement.getAttribute("packImgId");

            boolean loose = "true".equalsIgnoreCase(packElement.getAttribute("loose", "false"));
            String description = xmlCompilerHelper.requireChildNamed(packElement, "description").getContent();
            boolean required = xmlCompilerHelper.requireYesNoAttribute(packElement, "required", compilerData.getInstallFile());
            String group = packElement.getAttribute("group");
            String installGroups = packElement.getAttribute("installGroups");
            String excludeGroup = packElement.getAttribute("excludeGroup");
            boolean uninstall = "yes".equalsIgnoreCase(packElement.getAttribute("uninstall", "yes"));
            String parent = packElement.getAttribute("parent");
            boolean hidden = "true".equalsIgnoreCase(packElement.getAttribute("hidden", "false"));

            String conditionid = packElement.getAttribute("condition");

            if (required && excludeGroup != null) {
                AssertionHelper.parseError(packElement, "Pack, which has excludeGroup can not be required.", new Exception(
                        "Pack, which has excludeGroup can not be required."), compilerData.getInstallFile());
            }

            PackInfo pack = new PackInfo(name, id, description, required, loose, excludeGroup,
                    uninstall);
            pack.setOsConstraints(OsConstraint.getOsList(packElement)); // TODO:
            pack.setParent(parent);
            pack.setCondition(conditionid);
            pack.setHidden(hidden);
//            VariableSubstitutor varsubst = new VariableSubstitutorImpl(compiler.getVariables());

            // unverified
            // if the pack belongs to an excludeGroup it's not preselected by default
            if (excludeGroup == null) {
                pack.setPreselected(xmlCompilerHelper.validateYesNoAttribute(packElement, "preselected", YES, compilerData.getInstallFile()));
            } else {
                pack.setPreselected(xmlCompilerHelper.validateYesNoAttribute(packElement, "preselected", NO, compilerData.getInstallFile()));
            }

            // Set the pack group if specified
            if (group != null) {
                pack.setGroup(group);
            }
            // Set the pack install groups if specified
            if (installGroups != null) {
                StringTokenizer st = new StringTokenizer(installGroups, ",");
                while (st.hasMoreTokens()) {
                    String igroup = st.nextToken();
                    pack.addInstallGroup(igroup);
                }
            }

            // Set the packImgId if specified
            if (packImgId != null) {
                pack.setPackImgId(packImgId);
            }

            // We get the parsables list
            for (IXMLElement parsableNode : packElement.getChildrenNamed("parsable")) {
                String target = xmlCompilerHelper.requireAttribute(parsableNode, "targetfile", compilerData.getInstallFile());
                SubstitutionType type = SubstitutionType.lookup(parsableNode.getAttribute("type", "plain"));
                String encoding = parsableNode.getAttribute("encoding", null);
                List<OsConstraint> osList = OsConstraint.getOsList(parsableNode); // TODO: unverified
                String condition = parsableNode.getAttribute("condition");
                ParsableFile parsable = new ParsableFile(target, type, encoding, osList);
                parsable.setCondition(condition);
                pack.addParsable(parsable);
            }

            // We get the executables list
            for (IXMLElement executableNode : packElement.getChildrenNamed("executable")) {
                ExecutableFile executable = new ExecutableFile();
                String val; // temp value
                String condition = executableNode.getAttribute("condition");
                executable.setCondition(condition);
                executable.path = xmlCompilerHelper.requireAttribute(executableNode, "targetfile", compilerData.getInstallFile());

                // when to execute this executable
                val = executableNode.getAttribute("stage", "never");
                if ("postinstall".equalsIgnoreCase(val)) {
                    executable.executionStage = ExecutableFile.POSTINSTALL;
                } else if ("uninstall".equalsIgnoreCase(val)) {
                    executable.executionStage = ExecutableFile.UNINSTALL;
                }

                // type of this executable
                val = executableNode.getAttribute("type", "bin");
                if ("jar".equalsIgnoreCase(val)) {
                    executable.type = ExecutableFile.JAR;
                    executable.mainClass = executableNode.getAttribute("class"); // executable
                    // class
                }

                // what to do if execution fails
                val = executableNode.getAttribute("failure", "ask");
                if ("abort".equalsIgnoreCase(val)) {
                    executable.onFailure = ExecutableFile.ABORT;
                } else if ("warn".equalsIgnoreCase(val)) {
                    executable.onFailure = ExecutableFile.WARN;
                } else if ("ignore".equalsIgnoreCase(val)) {
                    executable.onFailure = ExecutableFile.IGNORE;
                }

                // whether to keep the executable after executing it
                val = executableNode.getAttribute("keep");
                executable.keepFile = "true".equalsIgnoreCase(val);

                // get arguments for this executable
                IXMLElement args = executableNode.getFirstChildNamed("args");
                if (null != args) {
                    for (IXMLElement ixmlElement : args.getChildrenNamed("arg")) {
                        executable.argList.add(xmlCompilerHelper.requireAttribute(ixmlElement, "value", compilerData.getInstallFile()));
                    }
                }

                executable.osList = OsConstraint.getOsList(executableNode); // TODO:
                // unverified

                pack.addExecutable(executable);
            }

            // We get the files list
            for (IXMLElement fileNode : packElement.getChildrenNamed("file")) {
                String src = xmlCompilerHelper.requireAttribute(fileNode, "src", compilerData.getInstallFile());
                String targetdir = xmlCompilerHelper.requireAttribute(fileNode, "targetdir", compilerData.getInstallFile());
                List<OsConstraint> osList = OsConstraint.getOsList(fileNode); // TODO: unverified
                OverrideType override = getOverrideValue(fileNode);
                Blockable blockable = getBlockableValue(fileNode, osList);
                Map additionals = getAdditionals(fileNode);
                boolean unpack = "true".equalsIgnoreCase(fileNode.getAttribute("unpack"));
                String condition = fileNode.getAttribute("condition");

                File file = new File(src);

                // if the path does not exist, maybe it contains variables
                if (!file.exists()) {
                    file = new File(variableSubstitutor.substitute(src));
                    // next existence check appears in pack.addFile
                }

                if (!file.isAbsolute()) {
                    file = new File(compilerData.getBasedir(), file.getPath());
                }

                try {
                    if (unpack) {
                        addArchiveContent(baseDir, file, targetdir, osList, override, blockable,
                                pack, additionals, condition);
                    } else {
                        addRecursively(baseDir, file, targetdir, osList, override, blockable,
                                pack, additionals, condition);
                    }
                }
                catch (Exception x) {
                    AssertionHelper.parseError(fileNode, x.getMessage(), x, compilerData.getInstallFile());
                }
            }

            // We get the singlefiles list
            for (IXMLElement singleFileNode : packElement.getChildrenNamed("singlefile")) {
                String src = xmlCompilerHelper.requireAttribute(singleFileNode, "src", compilerData.getInstallFile());
                String target = xmlCompilerHelper.requireAttribute(singleFileNode, "target", compilerData.getInstallFile());
                List<OsConstraint> osList = OsConstraint.getOsList(singleFileNode); // TODO: unverified
                OverrideType override = getOverrideValue(singleFileNode);
                Blockable blockable = getBlockableValue(singleFileNode, osList);
                Map additionals = getAdditionals(singleFileNode);
                String condition = singleFileNode.getAttribute("condition");
                File file = new File(src);
                if (!file.isAbsolute()) {
                    file = new File(compilerData.getBasedir(), src);
                }

                // if the path does not exist, maybe it contains variables
                if (!file.exists()) {
                    file = new File(variableSubstitutor.substitute(file.getAbsolutePath()));
                    // next existance checking appears in pack.addFile
                }

                try {
                    pack.addFile(baseDir, file, target, osList, override, blockable, additionals, condition);
                }
                catch (FileNotFoundException x) {
                    AssertionHelper.parseError(singleFileNode, x.getMessage(), x, compilerData.getInstallFile());
                }
            }

            // We get the fileset list
            for (IXMLElement fileSetNode : packElement.getChildrenNamed("fileset")) {
                String dir_attr = xmlCompilerHelper.requireAttribute(fileSetNode, "dir", compilerData.getInstallFile());

                File dir = new File(dir_attr);
                if (!dir.isAbsolute()) {
                    dir = new File(compilerData.getBasedir(), dir_attr);
                }
                if (!dir.isDirectory()) // also tests '.exists()'
                {
                    AssertionHelper.parseError(fileSetNode, "Invalid directory 'dir': " + dir_attr, compilerData.getInstallFile());
                }

                boolean casesensitive = xmlCompilerHelper.validateYesNoAttribute(fileSetNode, "casesensitive", YES, compilerData.getInstallFile());
                boolean defexcludes = xmlCompilerHelper.validateYesNoAttribute(fileSetNode, "defaultexcludes", YES, compilerData.getInstallFile());
                String targetdir = xmlCompilerHelper.requireAttribute(fileSetNode, "targetdir", compilerData.getInstallFile());
                List<OsConstraint> osList = OsConstraint.getOsList(fileSetNode); // TODO: unverified
                OverrideType override = getOverrideValue(fileSetNode);
                Blockable blockable = getBlockableValue(fileSetNode, osList);
                Map additionals = getAdditionals(fileSetNode);
                String condition = fileSetNode.getAttribute("condition");

                // get includes and excludes
                Vector<IXMLElement> xcludesList;
                String[] includes = null;
                xcludesList = fileSetNode.getChildrenNamed("include");
                if (!xcludesList.isEmpty()) {
                    includes = new String[xcludesList.size()];
                    for (int j = 0; j < xcludesList.size(); j++) {
                        IXMLElement xclude = xcludesList.get(j);
                        includes[j] = xmlCompilerHelper.requireAttribute(xclude, "name", compilerData.getInstallFile());
                    }
                }
                String[] excludes = null;
                xcludesList = fileSetNode.getChildrenNamed("exclude");
                if (!xcludesList.isEmpty()) {
                    excludes = new String[xcludesList.size()];
                    for (int j = 0; j < xcludesList.size(); j++) {
                        IXMLElement xclude = xcludesList.get(j);
                        excludes[j] = xmlCompilerHelper.requireAttribute(xclude, "name", compilerData.getInstallFile());
                    }
                }

                // parse additional fileset attributes "includes" and "excludes"
                String[] toDo = new String[]{"includes", "excludes"};
                // use the existing containers filled from include and exclude
                // and add the includes and excludes to it
                String[][] containers = new String[][]{includes, excludes};
                for (int j = 0; j < toDo.length; ++j) {
                    String inex = fileSetNode.getAttribute(toDo[j]);
                    if (inex != null && inex.length() > 0) { // This is the same "splitting" as ant PatternSet do ...
                        StringTokenizer tok = new StringTokenizer(inex, ", ", false);
                        int newSize = tok.countTokens();
                        int k = 0;
                        String[] nCont = null;
                        if (containers[j] != null && containers[j].length > 0) { // old container exist; create a new which can hold
                            // all values
                            // and copy the old stuff to the front
                            newSize += containers[j].length;
                            nCont = new String[newSize];
                            for (; k < containers[j].length; ++k) {
                                nCont[k] = containers[j][k];
                            }
                        }
                        if (nCont == null) // No container for old values
                        // created,
                        // create a new one.
                        {
                            nCont = new String[newSize];
                        }
                        for (; k < newSize; ++k)
                        // Fill the new one or expand the existent container
                        {
                            nCont[k] = tok.nextToken();
                        }
                        containers[j] = nCont;
                    }
                }
                includes = containers[0]; // push the new includes to the
                // local var
                excludes = containers[1]; // push the new excludes to the
                // local var

                // scan and add fileset
                DirectoryScanner ds = new DirectoryScanner();
                ds.setIncludes(includes);
                ds.setExcludes(excludes);
                if (defexcludes) {
                    ds.addDefaultExcludes();
                }
                ds.setBasedir(dir);
                ds.setCaseSensitive(casesensitive);
                ds.scan();

                String[] files = ds.getIncludedFiles();
                String[] dirs = ds.getIncludedDirectories();

                // Directory scanner has done recursion, add files and
                // directories
                for (String file : files) {
                    try {
                        String target = new File(targetdir, file).getPath();
                        pack.addFile(baseDir, new File(dir, file), target, osList, override,
                                blockable, additionals, condition);
                    }
                    catch (FileNotFoundException x) {
                        AssertionHelper.parseError(fileSetNode, x.getMessage(), x, compilerData.getInstallFile());
                    }
                }
                for (String dir1 : dirs) {
                    try {
                        String target = new File(targetdir, dir1).getPath();
                        pack.addFile(baseDir, new File(dir, dir1), target, osList, override,
                                blockable, additionals, condition);
                    }
                    catch (FileNotFoundException x) {
                        AssertionHelper.parseError(fileSetNode, x.getMessage(), x, compilerData.getInstallFile());
                    }
                }
            }

            // get the updatechecks list
            for (IXMLElement updateNode : packElement.getChildrenNamed("updatecheck")) {

                String casesensitive = updateNode.getAttribute("casesensitive");

                // get includes and excludes
                ArrayList<String> includesList = new ArrayList<String>();
                ArrayList<String> excludesList = new ArrayList<String>();

                // get includes and excludes
                for (IXMLElement ixmlElement1 : updateNode.getChildrenNamed("include")) {
                    includesList.add(xmlCompilerHelper.requireAttribute(ixmlElement1, "name", compilerData.getInstallFile()));
                }

                for (IXMLElement ixmlElement : updateNode.getChildrenNamed("exclude")) {
                    excludesList.add(xmlCompilerHelper.requireAttribute(ixmlElement, "name", compilerData.getInstallFile()));
                }

                pack.addUpdateCheck(new UpdateCheck(includesList, excludesList, casesensitive));
            }
            // We get the dependencies
            for (IXMLElement dependsNode : packElement.getChildrenNamed("depends")) {
                String depName = xmlCompilerHelper.requireAttribute(dependsNode, "packname", compilerData.getInstallFile());
                pack.addDependency(depName);

            }

            for (IXMLElement validatorNode : packElement.getChildrenNamed("validator")) {
                pack.addValidator(xmlCompilerHelper.requireContent(validatorNode));
            }

            // We add the pack
            compiler.addPack(pack);
        }

        for (IXMLElement refPackElement : refPackElements) {

            // get the name of reference xml file
            String refFileName = xmlCompilerHelper.requireAttribute(refPackElement, "file", compilerData.getInstallFile());
            String selfcontained = refPackElement.getAttribute("selfcontained");
            boolean isselfcontained = Boolean.valueOf(selfcontained);

            // parsing ref-pack-set file
            IXMLElement refXMLData = this.readRefPackData(refFileName, isselfcontained);

            Debug.log("Reading refpack from " + refFileName);
            // Recursively call myself to add all packs and refpacks from the reference XML
            addPacksSingle(refXMLData);
        }

        for (IXMLElement refPackSet : refPackSets) {

            // the directory to scan
            String dir_attr = xmlCompilerHelper.requireAttribute(refPackSet, "dir", this.compilerData.getInstallFile());

            File dir = new File(dir_attr);
            if (!dir.isAbsolute()) {
                dir = new File(compilerData.getBasedir(), dir_attr);
            }
            if (!dir.isDirectory()) // also tests '.exists()'
            {
                AssertionHelper.parseError(refPackSet, "Invalid refpackset directory 'dir': " + dir_attr, compilerData.getInstallFile());
            }

            // include pattern
            String includeString = xmlCompilerHelper.requireAttribute(refPackSet, "includes", this.compilerData.getInstallFile());
            String[] includes = includeString.split(", ");

            // scan for refpack files
            DirectoryScanner ds = new DirectoryScanner();
            ds.setIncludes(includes);
            ds.setBasedir(dir);
            ds.setCaseSensitive(true);
            ds.scan();

            // loop through all found fils and handle them as normal refpack files
            String[] files = ds.getIncludedFiles();
            for (String file : files) {
                String refFileName = new File(dir, file).toString();

                // parsing ref-pack-set file
                IXMLElement refXMLData = this.readRefPackData(refFileName, false);

                // Recursively call myself to add all packs and refpacks from the reference XML
                addPacksSingle(refXMLData);
            }
        }

        notifyCompilerListener("addPacksSingle", CompilerListener.END, data);
    }

    private IXMLElement readRefPackData(String refFileName, boolean isselfcontained)
            throws CompilerException {
        File refXMLFile = new File(refFileName);
        if (!refXMLFile.isAbsolute()) {
            refXMLFile = new File(compilerData.getBasedir(), refFileName);
        }
        if (!refXMLFile.canRead()) {
            throw new CompilerException("Invalid file: " + refXMLFile);
        }

        InputStream specin;

        if (isselfcontained) {
            if (!refXMLFile.getAbsolutePath().endsWith(".zip")) {
                throw new CompilerException(
                        "Invalid file: " + refXMLFile
                                + ". Selfcontained files can only be of type zip.");
            }
            ZipFile zip;
            try {
                zip = new ZipFile(refXMLFile, ZipFile.OPEN_READ);
                ZipEntry specentry = zip.getEntry("META-INF/izpack.xml");
                specin = zip.getInputStream(specentry);
            }
            catch (IOException e) {
                throw new CompilerException("Error reading META-INF/izpack.xml in " + refXMLFile);
            }
        } else {
            try {
                specin = new FileInputStream(refXMLFile.getAbsolutePath());
            }
            catch (FileNotFoundException e) {
                throw new CompilerException(
                        "FileNotFoundException exception while reading refXMLFile");
            }
        }

        IXMLParser refXMLParser = new XMLParser();
        // We get it
        IXMLElement refXMLData = refXMLParser.parse(specin, refXMLFile.getAbsolutePath());

        // Now checked the loaded XML file for basic syntax
        // We check it
        if (!"installation".equalsIgnoreCase(refXMLData.getName())) {
            AssertionHelper.parseError(refXMLData, "this is not an IzPack XML installation file", compilerData.getInstallFile());
        }
        if (!CompilerData.VERSION.equalsIgnoreCase(xmlCompilerHelper.requireAttribute(refXMLData, "version", compilerData.getInstallFile()))) {
            AssertionHelper.parseError(refXMLData, "the file version is different from the compiler version", compilerData.getInstallFile());
        }

        // Read the properties and perform replacement on the rest of the tree
        substituteProperties(refXMLData);

        // call addResources to add the referenced XML resources to this installation
        addResources(refXMLData);

        try {
            specin.close();
        }
        catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        return refXMLData;
    }

    /**
     * Add files in an archive to a pack
     *
     * @param archive     the archive file to unpack
     * @param targetdir   the target directory where the content of the archive will be installed
     * @param osList      The target OS constraints.
     * @param override    Overriding behaviour.
     * @param pack        Pack to be packed into
     * @param additionals Map which contains additional data
     * @param condition
     */
    protected void addArchiveContent(File baseDir, File archive, String targetdir,
                                     List<OsConstraint> osList, OverrideType override, Blockable blockable,
                                     PackInfo pack, Map additionals,
                                     String condition) throws IOException {

        FileInputStream fin = new FileInputStream(archive);
        ZipInputStream zin = new ZipInputStream(fin);
        while (true) {
            ZipEntry zentry = zin.getNextEntry();
            if (zentry == null) {
                break;
            }
            if (zentry.isDirectory()) {
                continue;
            }

            try {
                File temp = File.createTempFile("izpack", null);
                temp.deleteOnExit();

                FileOutputStream out = new FileOutputStream(temp);
                IoHelper.copyStream(zin, out);
                out.close();

                pack.addFile(baseDir, temp, targetdir + "/" + zentry.getName(), osList, override,
                        blockable, additionals, condition);
            }
            catch (IOException e) {
                throw new IOException("Couldn't create temporary file for " + zentry.getName()
                        + " in archive " + archive + " (" + e.getMessage() + ")");
            }

        }
        fin.close();
    }

    /**
     * Recursive method to add files in a pack.
     *
     * @param file        The file to add.
     * @param targetdir   The relative path to the parent.
     * @param osList      The target OS constraints.
     * @param override    Overriding behaviour.
     * @param pack        Pack to be packed into
     * @param additionals Map which contains additional data
     * @param condition
     * @throws FileNotFoundException if the file does not exist
     */
    protected void addRecursively(File baseDir, File file, String targetdir,
                                  List<OsConstraint> osList, OverrideType override, Blockable blockable,
                                  PackInfo pack, Map additionals, String condition) throws IOException {
        String targetfile = targetdir + "/" + file.getName();
        if (!file.isDirectory()) {
            pack.addFile(baseDir, file, targetfile, osList, override, blockable, additionals, condition);
        } else {
            File[] files = file.listFiles();
            if (files.length == 0) // The directory is empty so must be added
            {
                pack.addFile(baseDir, file, targetfile, osList, override, blockable, additionals, condition);
            } else {
                // new targetdir = targetfile;
                for (File file1 : files) {
                    addRecursively(baseDir, file1, targetfile, osList, override, blockable,
                            pack, additionals, condition);
                }
            }
        }
    }

    /**
     * Parse panels and their paramters, locate the panels resources and add to the Packager.
     *
     * @param data The XML data.
     * @throws CompilerException Description of the Exception
     */
    protected void addPanels(IXMLElement data) throws CompilerException {
        notifyCompilerListener("addPanels", CompilerListener.BEGIN, data);
        IXMLElement root = xmlCompilerHelper.requireChildNamed(data, "panels");

        // at least one panel is required
        Vector<IXMLElement> panels = root.getChildrenNamed("panel");
        if (panels.isEmpty()) {
            AssertionHelper.parseError(root, "<panels> requires a <panel>", compilerData.getInstallFile());
        }

        // We process each panel markup
        // We need a panel counter to build unique panel dependet resource names
        int panelCounter = 0;
        for (IXMLElement panel1 : panels) {
            panelCounter++;

            // create the serialized Panel data
            Panel panel = new Panel();
            panel.osConstraints = OsConstraint.getOsList(panel1);
            String className = panel1.getAttribute("classname");
            // add an id
            String panelid = panel1.getAttribute("id");
            panel.setPanelid(panelid);
            String condition = panel1.getAttribute("condition");
            panel.setCondition(condition);

            // Panel files come in jars packaged w/ IzPack, or they can be
            // specified via a jar attribute on the panel element
            String jarPath = panel1.getAttribute("jar");
            if (jarPath == null) {
                jarPath = "bin/panels/" + className + ".jar";
            }
            URL url = null;
            // jar="" may be used to suppress the warning message ("Panel jar
            // file not found")
            if (!jarPath.equals("")) {
                url = findIzPackResource(jarPath, "Panel jar file", panel1, true);
            }

            // when the expected panel jar file is not found, it is assumed that
            // user will do the jar merge themselves via <jar> tag

            String fullClassName = null;
            if (url == null) {
                fullClassName = className;
            } else {
                try {
                    fullClassName = compilerHelper.getFullClassName(url, className);
                }
                catch (IOException e) {
                }
            }

            if (fullClassName != null) {
                panel.className = fullClassName;
            } else {
                panel.className = className;
            }
            IXMLElement configurationElement = panel1.getFirstChildNamed("configuration");
            if (configurationElement != null) {
                Debug.trace("found a configuration for this panel.");
                Vector<IXMLElement> params = configurationElement.getChildrenNamed("param");
                if (params != null) {
                    for (IXMLElement param : params) {
                        IXMLElement keyElement = param.getFirstChildNamed("key");
                        IXMLElement valueElement = param.getFirstChildNamed("value");
                        if ((keyElement != null) && (valueElement != null)) {
                            panel.addConfiguration(keyElement.getContent(), valueElement.getContent());
                        }
                    }
                }
            }

            // adding validator
            IXMLElement validatorElement = panel1
                    .getFirstChildNamed(DataValidator.DATA_VALIDATOR_TAG);
            if (validatorElement != null) {
                String validator = validatorElement
                        .getAttribute(DataValidator.DATA_VALIDATOR_CLASSNAME_TAG);
                if (!"".equals(validator)) {
                    panel.setValidator(validator);
                }
            }
            // adding helps
            Vector helps = panel1.getChildrenNamed(AutomatedInstallData.HELP_TAG);
            if (helps != null) {
                for (Object help1 : helps) {
                    IXMLElement help = (IXMLElement) help1;
                    String iso3 = help.getAttribute(AutomatedInstallData.ISO3_ATTRIBUTE);
                    String resourceId;
                    if (panelid == null) {
                        resourceId = className + "_" + panelCounter + "_help_" + iso3 + ".html";
                    } else {
                        resourceId = panelid + "_" + panelCounter + "_help_" + iso3 + ".html";
                    }
                    panel.addHelp(iso3, resourceId);

                    URL originalUrl = findProjectResource(help
                            .getAttribute(AutomatedInstallData.SRC_ATTRIBUTE), "Help", help);
                    compiler.addResource(resourceId, originalUrl);
                }
            }
            // adding actions
            addPanelActions(panel1, panel);
            // insert into the packager
            compiler.addPanelJar(panel, url);
        }
        notifyCompilerListener("addPanels", CompilerListener.END, data);
    }

    /**
     * Adds the resources.
     *
     * @param data The XML data.
     * @throws CompilerException Description of the Exception
     */
    protected void addResources(IXMLElement data) throws CompilerException {
        notifyCompilerListener("addResources", CompilerListener.BEGIN, data);
        IXMLElement root = data.getFirstChildNamed("resources");
        if (root == null) {
            return;
        }

        // We process each res markup
        for (IXMLElement resNode : root.getChildrenNamed("res")) {
            String id = xmlCompilerHelper.requireAttribute(resNode, "id", compilerData.getInstallFile());
            String src = xmlCompilerHelper.requireAttribute(resNode, "src", compilerData.getInstallFile());
            // the parse attribute causes substitution to occur
            boolean substitute = xmlCompilerHelper.validateYesNoAttribute(resNode, "parse", NO, compilerData.getInstallFile());
            // the parsexml attribute causes the xml document to be parsed
            boolean parsexml = xmlCompilerHelper.validateYesNoAttribute(resNode, "parsexml", NO, compilerData.getInstallFile());

            String encoding = resNode.getAttribute("encoding");
            if (encoding == null) {
                encoding = "";
            }

            // basedir is not prepended if src is already an absolute path
            URL originalUrl = findProjectResource(src, "Resource", resNode);
            URL url = originalUrl;

            InputStream is = null;
            OutputStream os = null;
            try {
                if (!"".equals(encoding)) {
                    File recodedFile = File.createTempFile("izenc", null);
                    recodedFile.deleteOnExit();

                    InputStreamReader reader = new InputStreamReader(originalUrl.openStream(), encoding);
                    OutputStreamWriter writer = new OutputStreamWriter(
                            new FileOutputStream(recodedFile), "UTF-8");

                    char[] buffer = new char[1024];
                    int read;
                    while ((read = reader.read(buffer)) != -1) {
                        writer.write(buffer, 0, read);
                    }
                    reader.close();
                    writer.close();

                    originalUrl = recodedFile.toURL();
                }

                if (parsexml || (!"".equals(encoding)) || (substitute && !compiler.getVariables().isEmpty())) {
                    // make the substitutions into a temp file
                    File parsedFile = File.createTempFile("izpp", null);
                    parsedFile.deleteOnExit();
                    FileOutputStream outFile = new FileOutputStream(parsedFile);
                    os = new BufferedOutputStream(outFile);
                    // and specify the substituted file to be added to the
                    // packager
                    url = parsedFile.toURL();
                }

                if (parsexml) {
                    IXMLParser parser = new XMLParser();
                    // this constructor will open the specified url (this is
                    // why the InputStream is not handled in a similar manner
                    // to the OutputStream)

                    IXMLElement xml = parser.parse(originalUrl);
                    IXMLWriter writer = new XMLWriter();
                    if (substitute && !compiler.getVariables().isEmpty()) {
                        // if we are also performing substitutions on the file
                        // then create an in-memory copy to pass to the
                        // substitutor
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        writer.setOutput(baos);
                        is = new ByteArrayInputStream(baos.toByteArray());
                    } else {
                        // otherwise write direct to the temp file
                        writer.setOutput(os);
                    }
                    writer.write(xml);
                }

                // substitute variable values in the resource if parsed
                if (substitute) {
                    if (compiler.getVariables().isEmpty()) {
                        // reset url to original.
                        url = originalUrl;
                        AssertionHelper.parseWarn(resNode, "No variables defined. " + url.getPath() + " not parsed.", compilerData.getInstallFile());
                    } else {
                        SubstitutionType type = SubstitutionType.lookup(resNode.getAttribute("type"));

                        // if the xml parser did not open the url
                        // ('parsexml' was not enabled)
                        if (null == is) {
                            is = new BufferedInputStream(originalUrl.openStream());
                        }
//                        VariableSubstitutor vs = new VariableSubstitutorImpl(compiler.getVariables());
                        variableSubstitutor.substitute(is, os, type, "UTF-8");
                    }
                }

            }
            catch (Exception e) {
                AssertionHelper.parseError(resNode, e.getMessage(), e, compilerData.getInstallFile());
            }
            finally {
                if (null != os) {
                    try {
                        os.close();
                    }
                    catch (IOException e) {
                        // ignore as there is nothing we can realistically do
                        // so lets at least try to close the input stream
                    }
                }
                if (null != is) {
                    try {
                        is.close();
                    }
                    catch (IOException e) {
                        // ignore as there is nothing we can realistically do
                    }
                }
            }

            compiler.addResource(id, url);

            // remembering references to all added packsLang.xml files
            if (id.startsWith("packsLang.xml")) {
                List<URL> packsLangURLs;
                if (packsLangUrlMap.containsKey(id)) {
                    packsLangURLs = packsLangUrlMap.get(id);
                } else {
                    packsLangURLs = new ArrayList<URL>();
                    packsLangUrlMap.put(id, packsLangURLs);
                }
                packsLangURLs.add(url);
            }

        }
        notifyCompilerListener("addResources", CompilerListener.END, data);
    }

    /**
     * Adds the ISO3 codes of the langpacks and associated resources.
     *
     * @param data The XML data.
     * @throws CompilerException Description of the Exception
     */
    protected void addLangpacks(IXMLElement data) throws CompilerException {
        notifyCompilerListener("addLangpacks", CompilerListener.BEGIN, data);
        IXMLElement root = xmlCompilerHelper.requireChildNamed(data, "locale");

        // at least one langpack is required
        Vector<IXMLElement> locals = root.getChildrenNamed("langpack");
        if (locals.isEmpty()) {
            AssertionHelper.parseError(root, "<locale> requires a <langpack>", compilerData.getInstallFile());
        }

        // We process each langpack markup
        for (IXMLElement localNode : locals) {
            String iso3 = xmlCompilerHelper.requireAttribute(localNode, "iso3", compilerData.getInstallFile());
            String path;

            path = "bin/langpacks/installer/" + iso3 + ".xml";
            URL iso3xmlURL = findIzPackResource(path, "ISO3 file", localNode);

            path = "bin/langpacks/flags/" + iso3 + ".gif";
            URL iso3FlagURL = findIzPackResource(path, "ISO3 flag image", localNode);

            compiler.addLangPack(iso3, iso3xmlURL, iso3FlagURL);
        }
        notifyCompilerListener("addLangpacks", CompilerListener.END, data);
    }

    /**
     * Builds the Info class from the XML tree.
     *
     * @param data The XML data. return The Info.
     * @throws Exception Description of the Exception
     */
    protected void addInfo(IXMLElement data) throws Exception {
        notifyCompilerListener("addInfo", CompilerListener.BEGIN, data);
        // Initialisation
        IXMLElement root = xmlCompilerHelper.requireChildNamed(data, "info");

        Info info = new Info();
        info.setAppName(xmlCompilerHelper.requireContent(xmlCompilerHelper.requireChildNamed(root, "appname")));
        info.setAppVersion(xmlCompilerHelper.requireContent(xmlCompilerHelper.requireChildNamed(root, "appversion")));
        // We get the installation subpath
        IXMLElement subpath = root.getFirstChildNamed("appsubpath");
        if (subpath != null) {
            info.setInstallationSubPath(xmlCompilerHelper.requireContent(subpath));
        }

        // validate and insert app URL
        final IXMLElement URLElem = root.getFirstChildNamed("url");
        if (URLElem != null) {
            URL appURL = xmlCompilerHelper.requireURLContent(URLElem);
            info.setAppURL(appURL.toString());
        }

        // We get the authors list
        IXMLElement authors = root.getFirstChildNamed("authors");
        if (authors != null) {
            for (IXMLElement authorNode : authors.getChildrenNamed("author")) {
                String name = xmlCompilerHelper.requireAttribute(authorNode, "name", compilerData.getInstallFile());
                String email = xmlCompilerHelper.requireAttribute(authorNode, "email", compilerData.getInstallFile());
                info.addAuthor(new Info.Author(name, email));
            }
        }

        // We get the java version required
        IXMLElement javaVersion = root.getFirstChildNamed("javaversion");
        if (javaVersion != null) {
            info.setJavaVersion(xmlCompilerHelper.requireContent(javaVersion));
        }

        // Is a JDK required?
        IXMLElement jdkRequired = root.getFirstChildNamed("requiresjdk");
        if (jdkRequired != null) {
            info.setJdkRequired("yes".equals(jdkRequired.getContent()));
        }

        // validate and insert (and require if -web kind) web dir
        IXMLElement webDirURL = root.getFirstChildNamed("webdir");
        if (webDirURL != null) {
            info.setWebDirURL(xmlCompilerHelper.requireURLContent(webDirURL).toString());
        }
        String kind = compilerData.getKind();
        if (kind != null) {
            if (kind.equalsIgnoreCase(CompilerData.WEB) && webDirURL == null) {
                AssertionHelper.parseError(root, "<webdir> required when \"WEB\" installer requested", compilerData.getInstallFile());
            } else if (kind.equalsIgnoreCase(CompilerData.STANDARD) && webDirURL != null) {
                // Need a Warning? parseWarn(webDirURL, "Not creating web
                // installer.");
                info.setWebDirURL(null);
            }
        }

        // Pack200 support
        IXMLElement pack200 = root.getFirstChildNamed("pack200");
        info.setPack200Compression(pack200 != null);

        // Privileged execution
        IXMLElement privileged = root.getFirstChildNamed("run-privileged");
        info.setRequirePrivilegedExecution(privileged != null);
        if (privileged != null && privileged.hasAttribute("condition")) {
            info.setPrivilegedExecutionConditionID(privileged.getAttribute("condition"));
        }

        // Reboot if necessary
        IXMLElement reboot = root.getFirstChildNamed("rebootaction");
        if (reboot != null) {
            String content = reboot.getContent();
            if ("ignore".equalsIgnoreCase(content))
                info.setRebootAction(Info.REBOOT_ACTION_IGNORE);
            else if ("notice".equalsIgnoreCase(content))
                info.setRebootAction(Info.REBOOT_ACTION_NOTICE);
            else if ("ask".equalsIgnoreCase(content))
                info.setRebootAction(Info.REBOOT_ACTION_ASK);
            else if ("always".equalsIgnoreCase(content))
                info.setRebootAction(Info.REBOOT_ACTION_ALWAYS);
            else
                throw new CompilerException("Invalid value ''" + content + "'' of element ''reboot''");

            if (reboot.hasAttribute("condition")) {
                info.setRebootActionConditionID(reboot.getAttribute("condition"));
            }
        }

        // Add the uninstaller as a resource if specified
        IXMLElement uninstallInfo = root.getFirstChildNamed("uninstaller");
        if (xmlCompilerHelper.validateYesNoAttribute(uninstallInfo, "write", YES, compilerData.getInstallFile())) {
            URL url = findIzPackResource(propertyManager.getProperty("uninstaller"), "Uninstaller", root);
            compiler.addResource("IzPack.uninstaller", url);

            if (privileged != null) {
                // default behavior for uninstaller elevation: elevate if installer has to be elevated too
                info.setRequirePrivilegedExecutionUninstaller(xmlCompilerHelper.validateYesNoAttribute(privileged,
                        "uninstaller", YES, compilerData.getInstallFile()));
            }

            if (uninstallInfo != null) {
                String uninstallerName = uninstallInfo.getAttribute("name");
                if (uninstallerName != null && uninstallerName.length() > ".jar".length()) {
                    info.setUninstallerName(uninstallerName);
                }
                String uninstallerPath = uninstallInfo.getAttribute("path");
                if (uninstallerPath != null) {
                    info.setUninstallerPath(uninstallerPath);
                }
                if (uninstallInfo.hasAttribute("condition")) {
                    // there's a condition for uninstaller
                    String uninstallerCondition = uninstallInfo.getAttribute("condition");
                    info.setUninstallerCondition(uninstallerCondition);
                }
            }
        }

        // Add the path for the summary log file if specified
        IXMLElement slfPath = root.getFirstChildNamed("summarylogfilepath");
        if (slfPath != null) {
            info.setSummaryLogFilePath(xmlCompilerHelper.requireContent(slfPath));
        }

        IXMLElement writeInstallInfo = root.getFirstChildNamed("writeinstallationinformation");
        if (writeInstallInfo != null) {
            String writeInstallInfoString = xmlCompilerHelper.requireContent(writeInstallInfo);
            info.setWriteInstallationInformation(validateYesNo(writeInstallInfoString));
        }

        // look for an unpacker class
        String unpackerclass = propertyManager.getProperty("UNPACKER_CLASS");
        info.setUnpackerClassName(unpackerclass);
        compiler.setInfo(info);
        notifyCompilerListener("addInfo", CompilerListener.END, data);
    }

    /**
     * Variable declaration is a fragment of the xml file. For example: <p/>
     * <p/>
     * <pre>
     * &lt;p/&gt;
     * &lt;p/&gt;
     * &lt;p/&gt;
     * &lt;p/&gt;
     *        &lt;variables&gt;
     *          &lt;variable name=&quot;nom&quot; value=&quot;value&quot;/&gt;
     *          &lt;variable name=&quot;foo&quot; value=&quot;pippo&quot;/&gt;
     *        &lt;/variables&gt;
     * &lt;p/&gt;
     * &lt;p/&gt;
     * &lt;p/&gt;
     * &lt;p/&gt;
     * </pre>
     * <p/>
     * <p/> variable declared in this can be referred to in parsable files.
     *
     * @param data The XML data.
     * @throws CompilerException Description of the Exception
     */
    protected void addVariables(IXMLElement data) throws CompilerException {
        notifyCompilerListener("addVariables", CompilerListener.BEGIN, data);
        // We get the varible list
        IXMLElement root = data.getFirstChildNamed("variables");
        if (root == null) {
            return;
        }

        Properties variables = compiler.getVariables();

        for (IXMLElement variableNode : root.getChildrenNamed("variable")) {
            String name = xmlCompilerHelper.requireAttribute(variableNode, "name", compilerData.getInstallFile());
            String value = xmlCompilerHelper.requireAttribute(variableNode, "value", compilerData.getInstallFile());
            if (variables.contains(name)) {
                AssertionHelper.parseWarn(variableNode, "Variable '" + name + "' being overwritten", compilerData.getInstallFile());
            }
            variables.setProperty(name, value);
        }
        notifyCompilerListener("addVariables", CompilerListener.END, data);
    }

    protected void addDynamicVariables(IXMLElement data) throws CompilerException {
        notifyCompilerListener("addDynamicVariables", CompilerListener.BEGIN, data);
        // We get the dynamic variable list
        IXMLElement root = data.getFirstChildNamed("dynamicvariables");
        if (root == null) {
            return;
        }

        Map<String, List<DynamicVariable>> dynamicvariables = compiler.getDynamicVariables();

        for (IXMLElement variableNode : root.getChildrenNamed("variable")) {
            String name = xmlCompilerHelper.requireAttribute(variableNode, "name", compilerData.getInstallFile());
            String value = variableNode.getAttribute("value");
            if (value == null) {
                IXMLElement valueElement = variableNode.getFirstChildNamed("value");
                if (valueElement != null) {
                    value = valueElement.getContent();
                    if (value == null) {
                        AssertionHelper.parseError("A dynamic variable needs either a value attribute or a value element.", compilerData.getInstallFile());
                    }
                } else {
                    AssertionHelper.parseError("A dynamic variable needs either a value attribute or a value element. Variable name: " + name, compilerData.getInstallFile());
                }
            }
            String conditionid = variableNode.getAttribute("condition");

            List<DynamicVariable> dynamicValues = new ArrayList<DynamicVariable>();
            if (dynamicvariables.containsKey(name)) {
                dynamicValues = dynamicvariables.get(name);
            } else {
                dynamicvariables.put(name, dynamicValues);
            }

            DynamicVariable dynamicVariable = new DynamicVariable();
            dynamicVariable.setName(name);
            dynamicVariable.setValue(value);
            dynamicVariable.setConditionid(conditionid);
            if (dynamicValues.remove(dynamicVariable)) {
                AssertionHelper.parseWarn(variableNode, "Dynamic Variable '" + name + "' will be overwritten", compilerData.getInstallFile());
            }
            dynamicValues.add(dynamicVariable);
        }
        notifyCompilerListener("addDynamicVariables", CompilerListener.END, data);
    }

    /**
     * Parse conditions and add them to the compiler.
     *
     * @param data
     * @throws CompilerException
     */
    protected void addConditions(IXMLElement data) throws CompilerException {
        notifyCompilerListener("addConditions", CompilerListener.BEGIN, data);
        // We get the condition list
        IXMLElement root = data.getFirstChildNamed("conditions");
        Map<String, Condition> conditions = compiler.getConditions();
        if (root != null) {
            for (IXMLElement conditionNode : root.getChildrenNamed("condition")) {
                Condition condition = RulesEngineImpl.analyzeCondition(conditionNode);
                if (condition != null) {
                    String conditionid = condition.getId();
                    if (conditions.containsKey(conditionid)) {
                        AssertionHelper.parseWarn(conditionNode, "Condition with id '" + conditionid
                                + "' will be overwritten", compilerData.getInstallFile());
                    }
                    conditions.put(conditionid, condition);

                } else {
                    AssertionHelper.parseWarn(conditionNode, "Condition couldn't be instantiated.", compilerData.getInstallFile());
                }
            }
        }
        notifyCompilerListener("addConditions", CompilerListener.END, data);
    }

    /**
     * Properties declaration is a fragment of the xml file. For example: <p/>
     * <p/>
     * <pre>
     * &lt;p/&gt;
     * &lt;p/&gt;
     * &lt;p/&gt;
     * &lt;p/&gt;
     *        &lt;properties&gt;
     *          &lt;property name=&quot;app.name&quot; value=&quot;Property Laden Installer&quot;/&gt;
     *          &lt;!-- Ant styles 'location' and 'refid' are not yet supported --&gt;
     *          &lt;property file=&quot;filename-relative-to-install?&quot;/&gt;
     *          &lt;property file=&quot;filename-relative-to-install?&quot; prefix=&quot;prefix&quot;/&gt;
     *          &lt;!-- Ant style 'url' and 'resource' are not yet supported --&gt;
     *          &lt;property environment=&quot;prefix&quot;/&gt;
     *        &lt;/properties&gt;
     * &lt;p/&gt;
     * &lt;p/&gt;
     * &lt;p/&gt;
     * &lt;p/&gt;
     * </pre>
     * <p/>
     * <p/> variable declared in this can be referred to in parsable files.
     *
     * @param data The XML data.
     * @throws CompilerException Description of the Exception
     */
    protected void substituteProperties(IXMLElement data) throws CompilerException {
        notifyCompilerListener("substituteProperties", CompilerListener.BEGIN, data);

        IXMLElement root = data.getFirstChildNamed("properties");
        if (root != null) {
            // add individual properties
            for (IXMLElement propertyNode : root.getChildrenNamed("property")) {
                propertyManager.execute(propertyNode);
            }
        }

        // temporarily remove the 'properties' branch, replace all properties in
        // the remaining DOM, and replace properties branch.
        // TODO: enhance IXMLElement with an "indexOf(IXMLElement)" method
        // and addChild(IXMLElement, int) so returns to the same place.
        if (root != null) {
            data.removeChild(root);
        }

        substituteAllProperties(data);
        if (root != null) {
            data.addChild(root);
        }

        notifyCompilerListener("substituteProperties", CompilerListener.END, data);
    }

    /**
     * Perform recursive substitution on all properties
     */
    protected void substituteAllProperties(IXMLElement element) throws CompilerException {
        Enumeration attributes = element.enumerateAttributeNames();
        while (attributes.hasMoreElements()) {
            String name = (String) attributes.nextElement();
            String value = variableSubstitutor.substitute(element.getAttribute(name), SubstitutionType.TYPE_AT);
            element.setAttribute(name, value);
        }

        String content = element.getContent();
        if (content != null) {
            element.setContent(variableSubstitutor.substitute(content, SubstitutionType.TYPE_AT));
        }

        for (int i = 0; i < element.getChildren().size(); i++) {
            IXMLElement child = (IXMLElement) element.getChildren().elementAt(i);
            substituteAllProperties(child);
        }
    }

    /**
     * Returns the IXMLElement representing the installation XML file.
     *
     * @return The XML tree.
     * @throws CompilerException For problems with the installation file
     * @throws IOException       for errors reading the installation file
     */
    protected IXMLElement getXMLTree() throws IOException {
        IXMLParser parser = new XMLParser();
        IXMLElement data;
        if (compilerData.getInstallFile() != null) {
            File file = new File(compilerData.getInstallFile()).getAbsoluteFile();
            AssertionHelper.assertIsNormalReadableFile(file, "Configuration file");
            data = parser.parse(new FileInputStream(compilerData.getInstallFile()), file.getAbsolutePath());
            // add izpack built in property
            propertyManager.setProperty("izpack.file", file.toString());
        } else if (compilerData.getInstallText() != null) {
            data = parser.parse(compilerData.getInstallText());
        } else {
            throw new CompilerException("Neither install file nor text specified");
        }
        // We check it
        if (!"installation".equalsIgnoreCase(data.getName())) {
            AssertionHelper.parseError(data, "this is not an IzPack XML installation file", compilerData.getInstallFile());
        }
        if (!CompilerData.VERSION.equalsIgnoreCase(xmlCompilerHelper.requireAttribute(data, "version", compilerData.getInstallFile()))) {
            AssertionHelper.parseError(data, "the file version is different from the compiler version", compilerData.getInstallFile());
        }

        // We finally return the tree
        return data;
    }

    protected OverrideType getOverrideValue(IXMLElement f) throws CompilerException {
        String override_val = f.getAttribute("override");
        if (override_val == null) {
            return OverrideType.OVERRIDE_UPDATE;
        }

        OverrideType override = OverrideType.getOverrideTypeFromAttribute(override_val);
        if (override == null) {
            AssertionHelper.parseError(f, "invalid value for attribute \"override\"", compilerData.getInstallFile());
        }

        return override;
    }

    /**
     * Parses the blockable element value and adds automatically the OS constraint
     * family=windows if not already se in the given constraint list.
     * Throws a parsing warning if the constraint list was implicitely modified.
     *
     * @param f      the blockable XML element to parse
     * @param osList constraint list to maintain and return
     * @return blockable level
     * @throws CompilerException
     */
    protected Blockable getBlockableValue(IXMLElement f, List<OsConstraint> osList) throws CompilerException {
        String blockable_val = f.getAttribute("blockable");
        if (blockable_val == null) {
            return Blockable.BLOCKABLE_NONE;
        }
        Blockable blockable = Blockable.getBlockableFromAttribute(blockable_val);
        if (blockable == null) {
            AssertionHelper.parseError(f, "invalid value for attribute \"blockable\"", compilerData.getInstallFile());
        }

        if (blockable != Blockable.BLOCKABLE_NONE) {
            boolean found = false;
            for (OsConstraint anOsList : osList) {
                if ("windows".equals(anOsList.getFamily())) {
                    found = true;
                }
            }

            if (!found) {
                // We cannot add this constraint here explicitely, because it
                // the copied files might be multi-platform.
                // Print out a warning to inform the user about this fact.
                //osList.add(new OsConstraint("windows", null, null, null));
                AssertionHelper.parseWarn(f, "'blockable' will implicitely apply only on Windows target systems", compilerData.getInstallFile());
            }
        }
        return blockable;
    }

    /**
     * Look for a project specified resources, which, if not absolute, are sought relative to the
     * projects basedir. The path should use '/' as the fileSeparator. If the resource is not found,
     * a CompilerException is thrown indicating fault in the parent element.
     *
     * @param path   the relative path (using '/' as separator) to the resource.
     * @param desc   the description of the resource used to report errors
     * @param parent the IXMLElement the resource is specified in, used to report errors
     * @return a URL to the resource.
     */
    private URL findProjectResource(String path, String desc, IXMLElement parent)
            throws CompilerException {
        URL url = null;
        File resource = new File(path);
        if (!resource.isAbsolute()) {
            resource = new File(compilerData.getBasedir(), path);
        }

        if (!resource.exists()) // fatal
        {
            AssertionHelper.parseError(parent, desc + " not found: " + resource, compilerData.getInstallFile());
        }

        try {
            url = resource.toURL();
        }
        catch (MalformedURLException how) {
            AssertionHelper.parseError(parent, desc + "(" + resource + ")", how, compilerData.getInstallFile());
        }

        return url;
    }

    private URL findIzPackResource(String path, String desc, IXMLElement parent)
            throws CompilerException {
        return findIzPackResource(path, desc, parent, false);
    }

    /**
     * Look for an IzPack resource either in the compiler jar, or within IZPACK_HOME. The path must
     * not be absolute. The path must use '/' as the fileSeparator (it's used to access the jar
     * file). If the resource is not found, take appropriate action base on ignoreWhenNotFound flag.
     *
     * @param path               the relative path (using '/' as separator) to the resource.
     * @param desc               the description of the resource used to report errors
     * @param parent             the IXMLElement the resource is specified in, used to report errors
     * @param ignoreWhenNotFound when false, throws a CompilerException indicating
     *                           fault in the parent element when resource not found.
     * @return a URL to the resource.
     */
    private URL findIzPackResource(String path, String desc, IXMLElement parent, boolean ignoreWhenNotFound)
            throws CompilerException {
        URL url = getClass().getResource("/" + path);
        if (url == null) {
            File resource = new File(path);

            if (!resource.isAbsolute()) {
                resource = new File(CompilerData.IZPACK_HOME, path);
            }

            if (resource.exists()) {
                try {
                    url = resource.toURL();
                }
                catch (MalformedURLException how) {
                    AssertionHelper.parseError(parent, desc + "(" + resource + ")", how, compilerData.getInstallFile());
                }
            } else {
                if (ignoreWhenNotFound) {
                    AssertionHelper.parseWarn(parent, desc + " not found: " + resource, compilerData.getInstallFile());
                } else {
                    AssertionHelper.parseError(parent, desc + " not found: " + resource, compilerData.getInstallFile());
                }
            }

        }

        return url;
    }

    protected boolean validateYesNo(String value) {
        boolean result;
        if ("yes".equalsIgnoreCase(value)) {
            result = true;
        } else if ("no".equalsIgnoreCase(value)) {
            result = false;
        } else {
            Debug.trace("yes/no not found. trying true/false");
            result = Boolean.valueOf(value);
        }
        return result;
    }


    // -------------------------------------------------------------------------
    // ------------- Listener stuff ------------------------- START ------------

    /**
     * This method parses install.xml for defined listeners and put them in the right position. If
     * posible, the listeners will be validated. Listener declaration is a fragmention in
     * install.xml like : <listeners> <listener compiler="PermissionCompilerListener"
     * installer="PermissionInstallerListener"/> </<listeners>
     *
     * @param data the XML data
     * @throws Exception Description of the Exception
     */
    private void addCustomListeners(IXMLElement data) throws Exception {
        // We get the listeners
        IXMLElement root = data.getFirstChildNamed("listeners");
        if (root == null) {
            return;
        }
        for (IXMLElement ixmlElement : root.getChildrenNamed("listener")) {
            Object[] listener = getCompilerListenerInstance(ixmlElement);
            if (listener != null) {
                addCompilerListener((CompilerListener) listener[0]);
            }
            String[] typeNames = new String[]{"installer", "uninstaller"};
            int[] types = new int[]{CustomData.INSTALLER_LISTENER, CustomData.UNINSTALLER_LISTENER};

            for (int i = 0; i < typeNames.length; ++i) {
                String className = ixmlElement.getAttribute(typeNames[i]);
                if (className != null) {
                    // Check for a jar attribute on the listener
                    String jarPath = ixmlElement.getAttribute("jar");
                    jarPath = variableSubstitutor.substitute(jarPath, SubstitutionType.TYPE_AT);
                    if (jarPath == null) {
                        jarPath = "bin/customActions/" + className + ".jar";
                        if (!new File(jarPath).exists()) {
                            jarPath = compilerHelper.resolveCustomActionsJarPath(className);
                        }
                    }
                    List<OsConstraint> constraints = OsConstraint.getOsList(ixmlElement);
                    compiler.addCustomListener(types[i], className, jarPath, constraints);
                }
            }
        }

    }

    /**
     * Returns the compiler listener which is defined in the xml element. As xml element a "listner"
     * node will be expected. Additional it is expected, that either "findIzPackResource" returns an
     * url based on "bin/customActions/[className].jar", or that the listener element has a jar
     * attribute specifying the listener jar path. The class will be loaded via an URLClassLoader.
     *
     * @param var the xml element of the "listener" node
     * @return instance of the defined compiler listener
     * @throws Exception
     */
    private Object[] getCompilerListenerInstance(IXMLElement var) throws Exception {
        String className = var.getAttribute("compiler");
        Class listener = null;
        Object instance = null;
        if (className == null) {
            return (null);
        }

        // CustomAction files come in jars packaged IzPack, or they can be
        // specified via a jar attribute on the listener
        String jarPath = var.getAttribute("jar");
        jarPath = variableSubstitutor.substitute(jarPath, SubstitutionType.TYPE_AT);
        if (jarPath == null) {
            jarPath = "bin/customActions/" + className + ".jar";
        }
        URL url = findIzPackResource(jarPath, "CustomAction jar file", var);
        String fullName = compilerHelper.getFullClassName(url, className);
        if (fullName == null) {
            // class not found
            return null;
        }
        if (url != null) {
            if (getClass().getResource("/" + jarPath) != null) { // Oops, standalone, URLClassLoader will not work ...
                // Write the jar to a temp file.
                InputStream in = null;
                FileOutputStream outFile = null;
                byte[] buffer = new byte[5120];
                File tf = null;
                try {
                    tf = File.createTempFile("izpj", ".jar");
                    tf.deleteOnExit();
                    outFile = new FileOutputStream(tf);
                    in = getClass().getResourceAsStream("/" + jarPath);
                    long bytesCopied = 0;
                    int bytesInBuffer;
                    while ((bytesInBuffer = in.read(buffer)) != -1) {
                        outFile.write(buffer, 0, bytesInBuffer);
                        bytesCopied += bytesInBuffer;
                    }
                }
                finally {
                    if (in != null) {
                        in.close();
                    }
                    if (outFile != null) {
                        outFile.close();
                    }
                }
                url = tf.toURL();

            }
            // Use the class loader of the interface as parent, else
            // compile will fail at using it via an Ant task.
            URLClassLoader ucl = new URLClassLoader(new URL[]{url}, CompilerListener.class
                    .getClassLoader());
            listener = ucl.loadClass(fullName);
        }
        if (listener != null) {
            instance = listener.newInstance();
        } else {
            AssertionHelper.parseError(var, "Cannot find defined compiler listener " + className, compilerData.getInstallFile());
        }
        if (!CompilerListener.class.isInstance(instance)) {
            AssertionHelper.parseError(var, "'" + className + "' must be implemented "
                    + CompilerListener.class.toString(), compilerData.getInstallFile());
        }
        List<OsConstraint> constraints = OsConstraint.getOsList(var);
        return (new Object[]{instance, className, constraints});
    }

    /**
     * Add a CompilerListener. A registered CompilerListener will be called at every enhancmend
     * point of compiling.
     *
     * @param pe CompilerListener which should be added
     */
    private void addCompilerListener(CompilerListener pe) {
        compilerListeners.add(pe);
    }

    /**
     * Calls all defined compile listeners notify method with the given data
     *
     * @param callerName name of the calling method as string
     * @param state      CompileListener.BEGIN or END
     * @param data       current install data
     * @throws CompilerException
     */
    private void notifyCompilerListener(String callerName, int state, IXMLElement data) {
        IPackager packager = compiler.getPackager();
        for (CompilerListener compilerListener : compilerListeners) {
            compilerListener.notify(callerName, state, data, packager);
        }

    }

    /**
     * Calls the reviseAdditionalDataMap method of all registered CompilerListener's.
     *
     * @param f file releated XML node
     * @return a map with the additional attributes
     */
    private Map getAdditionals(IXMLElement f) throws CompilerException {
        Map retval = null;
        try {
            for (CompilerListener compilerListener : compilerListeners) {
                retval = (compilerListener).reviseAdditionalDataMap(retval, f);
            }
        }
        catch (CompilerException ce) {
            AssertionHelper.parseError(f, ce.getMessage(), compilerData.getInstallFile());
        }
        return (retval);
    }

    /**
     * A function to merge multiple packsLang-files into a single file for each identifier, e.g. two
     * resource files
     * <p/>
     * <pre>
     *    &lt;res src=&quot;./packsLang01.xml&quot; id=&quot;packsLang.xml&quot;/&gt;
     *    &lt;res src=&quot;./packsLang02.xml&quot; id=&quot;packsLang.xml&quot;/&gt;
     * </pre>
     * <p/>
     * are merged into a single temp-file to act as if the user had defined:
     * <p/>
     * <pre>
     *    &lt;res src=&quot;/tmp/izpp47881.tmp&quot; id=&quot;packsLang.xml&quot;/&gt;
     * </pre>
     *
     * @throws CompilerException
     */
    private void mergePacksLangFiles() throws CompilerException {
        // just one packslang file. nothing to do here
        if (packsLangUrlMap.size() <= 0) return;

        OutputStream os = null;
        try {
            IXMLParser parser = new XMLParser();

            // loop through all packsLang resources, e.g. packsLang.xml_eng, packsLang.xml_deu, ...
            for (String id : packsLangUrlMap.keySet()) {
                URL mergedPackLangFileURL;

                List<URL> packsLangURLs = packsLangUrlMap.get(id);
                if (packsLangURLs.size() == 0) continue; // should not occure

                if (packsLangURLs.size() == 1) {
                    // no need to merge files. just use the first URL
                    mergedPackLangFileURL = packsLangURLs.get(0);
                } else {
                    IXMLElement mergedPacksLang = null;

                    // loop through all that belong to the given identifier
                    for (URL packslangURL : packsLangURLs) {
                        // parsing xml
                        IXMLElement xml = parser.parse(packslangURL);
                        if (mergedPacksLang == null) {
                            // just keep the first file
                            mergedPacksLang = xml;
                        } else {
                            // append data of all xml-docs into the first document
                            Vector<IXMLElement> langStrings = xml.getChildrenNamed("str");
                            for (IXMLElement langString : langStrings) {
                                mergedPacksLang.addChild(langString);
                            }
                        }
                    }

                    // writing merged strings to a new file
                    File mergedPackLangFile = File.createTempFile("izpp", null);
                    mergedPackLangFile.deleteOnExit();

                    FileOutputStream outFile = new FileOutputStream(mergedPackLangFile);
                    os = new BufferedOutputStream(outFile);

                    IXMLWriter xmlWriter = new XMLWriter(os);
                    xmlWriter.write(mergedPacksLang);
                    os.close();
                    os = null;

                    // getting the URL to the new merged file
                    mergedPackLangFileURL = mergedPackLangFile.toURL();
                }

                compiler.addResource(id, mergedPackLangFileURL);
            }
        }
        catch (Exception e) {
            throw new CompilerException("Unable to merge multiple packsLang.xml files: "
                    + e.getMessage(), e);
        }
        finally {
            if (null != os) {
                try {
                    os.close();
                }
                catch (IOException e) {
                    // ignore as there is nothing we can realistically do
                    // so lets at least try to close the input stream
                }
            }
        }
    }

    /**
     * @param xmlPanel
     * @param panel
     * @throws CompilerException
     */
    private void addPanelActions(IXMLElement xmlPanel, Panel panel) throws CompilerException {
        IXMLElement xmlActions = xmlPanel.getFirstChildNamed(PanelAction.PANEL_ACTIONS_TAG);
        if (xmlActions != null) {
            Vector<IXMLElement> actionList = xmlActions
                    .getChildrenNamed(PanelAction.PANEL_ACTION_TAG);
            if (actionList != null) {
                for (IXMLElement action : actionList) {
                    String stage = action.getAttribute(PanelAction.PANEL_ACTION_STAGE_TAG);
                    String actionName = action.getAttribute(PanelAction.PANEL_ACTION_CLASSNAME_TAG);
                    if (actionName != null) {
                        Vector<IXMLElement> params = action.getChildrenNamed("param");
                        PanelActionConfiguration config = new PanelActionConfiguration();

                        for (IXMLElement param : params) {
                            IXMLElement keyElement = param.getFirstChildNamed("key");
                            IXMLElement valueElement = param.getFirstChildNamed("value");
                            if ((keyElement != null) && (valueElement != null)) {
                                Debug.trace("Adding configuration property " + keyElement.getContent() + " with value " + valueElement.getContent() + " for action " + actionName);
                                config.addProperty(keyElement.getContent(), valueElement.getContent());
                            }
                        }
                        panel.putPanelActionConfiguration(actionName, config);
                    }
                    try {
                        ActionStage actionStage = ActionStage.valueOf(stage);
                        switch (actionStage) {
                            case preconstruct:
                                panel.addPreConstructionActions(actionName);
                                break;
                            case preactivate:
                                panel.addPreActivationAction(actionName);
                                break;
                            case prevalidate:
                                panel.addPreValidationAction(actionName);
                                break;
                            case postvalidate:
                                panel.addPostValidationAction(actionName);
                                break;
                        }
                    }
                    catch (IllegalArgumentException e) {
                        AssertionHelper.parseError(action, "Invalid value [" + stage + "] for attribute : "
                                + PanelAction.PANEL_ACTION_STAGE_TAG, compilerData.getInstallFile());
                    }
                }
            } else {
                AssertionHelper.parseError(xmlActions, "<" + PanelAction.PANEL_ACTIONS_TAG + "> requires a <"
                        + PanelAction.PANEL_ACTION_TAG + ">", compilerData.getInstallFile());
            }
        }
    }

}