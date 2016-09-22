package me.nicolaferraro.bom;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import org.apache.commons.io.IOUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.internal.LifecycleModuleBuilder;
import org.apache.maven.lifecycle.internal.LifecycleTaskSegmentCalculator;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
import org.eclipse.aether.resolution.ArtifactRequest;

import me.nicolaferraro.bom.config.Bom;
import me.nicolaferraro.bom.util.DependencyMatcher;


@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_RESOURCES)
public class BomGeneratorMojo extends AbstractMojo {

    private static final String THIS_PLUGIN_GROUP_ID = "me.nicolaferraro.bom";
    private static final String THIS_PLUGIN_ARTIFACT_ID = "bom-generator-maven-plugin";

    @Parameter(property = "project")
    private MavenProject project;

    @Component
    private RepositorySystem system;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession session;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    private List<RemoteRepository> remoteRepositories;

    @Parameter(defaultValue = "${reactorProjects}", required = true, readonly = false)
    private List<MavenProject> reactorProjects;

    @Parameter(property = "session", readonly = true)
    private MavenSession mavenSession;

    @Parameter(defaultValue = "${maven.version}")
    private String mavenVersion;

    @Component
    private LifecycleModuleBuilder builder;

    @Component
    private LifecycleTaskSegmentCalculator segmentCalculator;

    @Parameter(defaultValue = "${basedir}/pom.xml")
    protected File sourcePom;

    @Parameter(defaultValue = "${project.build.directory}/${project.artifactId}-bom.xml")
    protected File targetPom;

    @Parameter(defaultValue = "${project.artifactId}-bom")
    protected String name;

    @Parameter
    private List<Bom> boms;

    @Parameter
    private List<String> preferences;

    @Parameter
    private List<String> goals;

    public void execute() throws MojoExecutionException, MojoFailureException {
        try {

            Map<String, List<Dependency>> dependencies = resolveValidDependencies();
            Set<String> resolved = crossCheckDependenciesAndResolve(dependencies);

            List<Dependency> finalDependencies = flatten(dependencies);
            finalDependencies = applyResolution(finalDependencies, resolved);

            Document pom = loadBasePom();
            this.overwriteValues(pom, finalDependencies);
            File pomFile = writePom(pom);

//            MavenProject generatedProject = loadExternalProjectPom(pomFile);
//            build(mavenSession.clone(), generatedProject, Collections.singletonList(generatedProject), Collections.singletonList("install"));

        } catch (MojoExecutionException | MojoFailureException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Cannot generate the BOM", e);
        }


    }

    private Map<String, List<Dependency>> resolveValidDependencies() throws Exception {
        Map<String, List<Dependency>> validDependencies = new HashMap<>();
        if (boms != null) {
            for (Bom bom : boms) {
                String key = bomKey(bom);
                if (validDependencies.containsKey(key)) {
                    throw new IllegalStateException("Bom " + key + " is contained twice");
                }

                List<Dependency> dependencies = resolveValidDependencies(bom);
                validDependencies.put(key, dependencies);
            }
        }

        validDependencies.put("self", resolveSelfDependencies());

        return validDependencies;
    }


    private List<Dependency> resolveSelfDependencies() throws Exception {
        List<Dependency> dependencies = new ArrayList<>();
        if (project.getDependencyManagement() != null && project.getDependencyManagement().getDependencies() != null) {
            for (org.apache.maven.model.Dependency dep : project.getDependencyManagement().getDependencies()) {
                Artifact artifact = new DefaultArtifact(dep.getGroupId(), dep.getArtifactId(), dep.getClassifier(), dep.getType(), dep.getVersion());
                List<Exclusion> exclusions = new LinkedList<>();
                for (org.apache.maven.model.Exclusion ex : dep.getExclusions()) {
                    Exclusion aexl = new Exclusion(ex.getGroupId(), ex.getArtifactId(), null, null);
                    exclusions.add(aexl);
                }
                Dependency dependency = new Dependency(artifact, dep.getScope(), dep.isOptional(), exclusions);
                dependencies.add(dependency);
            }
        }

        return dependencies;
    }

    private List<Dependency> resolveValidDependencies(Bom bom) throws Exception {
        getLog().info("Resolving " + bom + " to get managed dependencies ");
        Artifact artifact = new DefaultArtifact(bom.getGroupId(), bom.getArtifactId(), "pom", bom.getVersion());

        ArtifactRequest artifactRequest = new ArtifactRequest(artifact, remoteRepositories, null);
        system.resolveArtifact(session, artifactRequest); // To get an error when the artifact does not exist

        ArtifactDescriptorRequest req = new ArtifactDescriptorRequest(artifact, remoteRepositories, null);
        ArtifactDescriptorResult res = system.readArtifactDescriptor(session, req);

        List<Dependency> validDependencies = new ArrayList<>();
        if (res.getManagedDependencies() != null) {

            DependencyMatcher inclusions = new DependencyMatcher(bom.getIncludes());
            DependencyMatcher exclusions = new DependencyMatcher(bom.getExcludes());

            for (Dependency dependency : res.getManagedDependencies()) {

                boolean accept = inclusions.matches(dependency) && !exclusions.matches(dependency);
                getLog().debug(dependency + (accept ? " included in the BOM" : " excluded from BOM"));

                if (accept) {
                    validDependencies.add(dependency);
                }
            }
        }

        int included = validDependencies.size();
        int total = res.getManagedDependencies() != null ? res.getManagedDependencies().size() : 0;

        getLog().info("Included " + included + "/" + total + " dependencies from BOM " + bomKey(bom));
        return validDependencies;
    }

    private Set<String> crossCheckDependenciesAndResolve(Map<String, List<Dependency>> dependencies) throws MojoFailureException {

        // Build a version lookup table for faster check
        Map<String, Map<String, Dependency>> lookupTable = new HashMap<>();
        for (String bom : dependencies.keySet()) {
            Map<String, Dependency> bomLookup = new HashMap<>();
            lookupTable.put(bom, bomLookup);

            for (Dependency dependency : dependencies.get(bom)) {
                String key = dependencyKey(dependency);
                bomLookup.put(key, dependency);
            }
        }

        Map<String, Set<VersionInfo>> inconsistencies = new TreeMap<>();
        Set<String> trueInconsistencies = new TreeSet<>();

        // Cross-check all dependencies
        for (String bom1 : dependencies.keySet()) {
            for (Dependency dependency1 : dependencies.get(bom1)) {
                String key = dependencyKey(dependency1);
                String version1 = dependency1.getArtifact().getVersion();

                for (String bom2 : dependencies.keySet()) {
                    // Some boms have conflicts with themselves

                    Dependency dependency2 = lookupTable.get(bom2).get(key);
                    String version2 = dependency2 != null ? dependency2.getArtifact().getVersion() : null;
                    if (version2 != null) {
                        Set<VersionInfo> inconsistency = inconsistencies.get(key);
                        if (inconsistency == null) {
                            inconsistency = new TreeSet<>();
                            inconsistencies.put(key, inconsistency);
                        }

                        inconsistency.add(new VersionInfo(dependency1, bom1));
                        inconsistency.add(new VersionInfo(dependency2, bom2));

                        if (!version2.equals(version1)) {
                            trueInconsistencies.add(key);
                        }
                    }
                }
            }
        }

        // Try to solve with preferences
        Set<String> resolvedInconsistencies = new TreeSet<>();
        DependencyMatcher preferencesMatcher = new DependencyMatcher(this.preferences);
        for (String key : trueInconsistencies) {
            int preferred = 0;
            for (VersionInfo nfo : inconsistencies.get(key)) {
                if (preferencesMatcher.matches(nfo.getDependency())) {
                    preferred++;
                }
            }

            if (preferred == 1) {
                resolvedInconsistencies.add(key);
            }
        }

        trueInconsistencies.removeAll(resolvedInconsistencies);

        if (trueInconsistencies.size() > 0) {
            StringBuilder message = new StringBuilder();
            message.append("Found " + trueInconsistencies.size() + " inconsistencies in the generated BOM.\n");

            for (String key : trueInconsistencies) {
                message.append(key);
                message.append(" has different versions:\n");
                for (VersionInfo nfo : inconsistencies.get(key)) {
                    message.append(" - ");
                    message.append(nfo);
                    message.append("\n");
                }
            }

            throw new MojoFailureException(message.toString());
        }

        return resolvedInconsistencies;
    }

    private List<Dependency> flatten(Map<String, List<Dependency>> dependencies) {
        List<Dependency> flatList = new ArrayList<>();
        for (String bom : dependencies.keySet()) {
            for (Dependency dep : dependencies.get(bom)) {
                flatList.add(dep);
            }
        }

        Collections.sort(flatList, (d1, d2) -> dependencyKey(d1).compareTo(dependencyKey(d2)));
        return flatList;
    }

    private List<Dependency> applyResolution(List<Dependency> dependencies, Set<String> solvable) {
        DependencyMatcher preferencesMatcher = new DependencyMatcher(this.preferences);
        List<Dependency> res = new ArrayList<>();
        for (Dependency dep : dependencies) {
            String key = dependencyKey(dep);
            if(solvable.contains(key)) {
                if(preferencesMatcher.matches(dep)) {
                    res.add(dep);
                }
            } else {
                res.add(dep);
            }
        }

        return res;
    }

    private Document loadBasePom() throws Exception {
        DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        Document pom = builder.parse(sourcePom);
        return pom;
    }

    private void overwriteValues(Document pom, List<Dependency> dependencies) throws Exception {
        overwriteName(pom, this.name);
        deleteThisPlugin(pom);
        overwriteDependencyManagement(pom, dependencies);
    }

    private void overwriteName(Document pom, String name) throws Exception {
        XPath xpath = XPathFactory.newInstance().newXPath();
        XPathExpression expr = xpath.compile("/project/artifactId");
        NodeList nodes = (NodeList) expr.evaluate(pom, XPathConstants.NODESET);
        if (nodes.getLength() == 0) {
            throw new IllegalStateException("No artifactId found in the current pom");
        }
        Element el = (Element) nodes.item(0);
        el.setTextContent(name);
    }

    private void deleteThisPlugin(Document pom) throws Exception {
        XPath xpath = XPathFactory.newInstance().newXPath();

        XPathExpression expr = xpath.compile("/project/build/plugins/plugin[./groupId='" + THIS_PLUGIN_GROUP_ID + "'][./artifactId='" + THIS_PLUGIN_ARTIFACT_ID + "']");
        NodeList nodes = (NodeList) expr.evaluate(pom, XPathConstants.NODESET);
        delete(nodes);

        expr = xpath.compile("/project/build/plugins");
        NodeList plugins = (NodeList) expr.evaluate(pom, XPathConstants.NODESET);
        if (!hasChildElements(plugins)) {
            delete(plugins);
        }

        expr = xpath.compile("/project/build");
        NodeList build = (NodeList) expr.evaluate(pom, XPathConstants.NODESET);
        if (!hasChildElements(build)) {
            delete(build);
        }
    }

    private void delete(NodeList nodes) {
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            node.getParentNode().removeChild(node);
        }
    }

    private boolean hasChildElements(NodeList nodes) {
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            NodeList children = node.getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                Node child = children.item(j);
                if (child instanceof Element) {
                    return true;
                }
            }
        }
        return false;
    }

    private void overwriteDependencyManagement(Document pom, List<Dependency> dependencies) throws Exception {
        Element dependencyManagementSection = getOrCreate(pom, pom.getDocumentElement(), "dependencyManagement");
        Element dependenciesSection = getOrCreate(pom, dependencyManagementSection, "dependencies");

        // cleanup the dependency management section
        while (dependenciesSection.hasChildNodes()) {
            Node child = dependenciesSection.getFirstChild();
            dependenciesSection.removeChild(child);
        }

        for (Dependency dep : dependencies) {
            Artifact artifact = dep.getArtifact();
            Element dependencyEl = pom.createElement("dependency");

            Element groupIdEl = pom.createElement("groupId");
            groupIdEl.setTextContent(artifact.getGroupId());
            dependencyEl.appendChild(groupIdEl);

            Element artifactIdEl = pom.createElement("artifactId");
            artifactIdEl.setTextContent(artifact.getArtifactId());
            dependencyEl.appendChild(artifactIdEl);

            Element versionEl = pom.createElement("version");
            versionEl.setTextContent(artifact.getVersion());
            dependencyEl.appendChild(versionEl);

            if (!"jar".equals(artifact.getExtension())) {
                Element typeEl = pom.createElement("type");
                typeEl.setTextContent(artifact.getExtension());
                dependencyEl.appendChild(typeEl);
            }

            if (artifact.getClassifier() != null && artifact.getClassifier().trim().length() > 0) {
                Element classifierEl = pom.createElement("classifier");
                classifierEl.setTextContent(artifact.getClassifier());
                dependencyEl.appendChild(classifierEl);
            }

            if (dep.getScope() != null && dep.getScope().trim().length() > 0 && !"compile".equals(dep.getScope())) {
                Element scopeEl = pom.createElement("scope");
                scopeEl.setTextContent(dep.getScope());
                dependencyEl.appendChild(scopeEl);
            }

            if (dep.getExclusions() != null && !dep.getExclusions().isEmpty()) {

                Element exclsEl = pom.createElement("exclusions");

                for (Exclusion e : dep.getExclusions()) {
                    Element exclEl = pom.createElement("exclusion");

                    Element groupIdExEl = pom.createElement("groupId");
                    groupIdExEl.setTextContent(e.getGroupId());
                    exclEl.appendChild(groupIdExEl);

                    Element artifactIdExEl = pom.createElement("artifactId");
                    artifactIdExEl.setTextContent(e.getArtifactId());
                    exclEl.appendChild(artifactIdExEl);

                    exclsEl.appendChild(exclEl);
                }

                dependencyEl.appendChild(exclsEl);
            }

            dependenciesSection.appendChild(dependencyEl);
        }
    }

    private Element getOrCreate(Document document, Element parent, String name) {
        NodeList children = parent.getElementsByTagName(name);
        if (children == null || children.getLength() == 0) {
            // create
            Element element = document.createElement(name);
            parent.appendChild(element);
            return element;
        }
        return (Element) children.item(0);
    }

    private File writePom(Document pom) throws Exception {
        XPathExpression xpath = XPathFactory.newInstance().newXPath().compile("//text()[normalize-space(.) = '']");
        NodeList emptyNodes = (NodeList) xpath.evaluate(pom, XPathConstants.NODESET);

        // Remove empty text nodes
        for (int i = 0; i < emptyNodes.getLength(); i++) {
            Node emptyNode = emptyNodes.item(i);
            emptyNode.getParentNode().removeChild(emptyNode);
        }

        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.METHOD, "xml");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

        DOMSource source = new DOMSource(pom);

        targetPom.getParentFile().mkdirs();

        String content;
        try (StringWriter out = new StringWriter()) {
            StreamResult result = new StreamResult(out);
            transformer.transform(source, result);
            content = out.toString();
        }

        // Fix header formatting problem
        content = content.replaceFirst("-->", "-->\n");
        writeFileIfChanged(content, targetPom);
        return targetPom;
    }

    private void writeFileIfChanged(String content, File file) throws IOException {
        boolean write = true;

        if (file.exists()) {
            try (FileReader fr = new FileReader(file)) {
                String oldContent = IOUtils.toString(fr);
                if (!content.equals(oldContent)) {
                    getLog().debug("Writing new file " + file.getAbsolutePath());
                    fr.close();
                } else {
                    getLog().debug("File " + file.getAbsolutePath() + " left unchanged");
                    write = false;
                }
            }
        } else {
            File parent = file.getParentFile();
            parent.mkdirs();
        }

        if (write) {
            try (FileWriter fw = new FileWriter(file)) {
                IOUtils.write(content, fw);
            }
        }
    }

//    private MavenProject loadExternalProjectPom(File pomFile) throws Exception {
//        try (FileReader reader = new FileReader(pomFile)) {
//            MavenXpp3Reader mavenReader = new MavenXpp3Reader();
//            Model model = mavenReader.read(reader);
//
//            MavenProject project = new MavenProject(model);
//            project.setFile(pomFile);
//            return project;
//        }
//    }

//    private void build(MavenSession session, MavenProject project, List<MavenProject> allProjects, List<String> goals) throws MojoExecutionException {
//        session.setAllProjects(allProjects);
//        session.setProjects(allProjects);
//
//        ProjectIndex projectIndex = new ProjectIndex(session.getProjects());
//        try {
//            ReactorBuildStatus reactorBuildStatus = new ReactorBuildStatus(new BomDependencyGraph(session.getProjects()));
//            ReactorContext reactorContext = new ReactorContextFactory(new MavenVersion(mavenVersion)).create(session.getResult(), projectIndex, Thread.currentThread().getContextClassLoader(),
// reactorBuildStatus, builder);
//            List<TaskSegment> segments = segmentCalculator.calculateTaskSegments(session);
//            for (TaskSegment segment : segments) {
//                getLog().info("CCC " + segment);
//                builder.buildProject(session, reactorContext, project, segment);
//            }
//            getLog().info("FFF " + reactorContext.getResult().getBuildSummary(project).toString());
//
//
//        } catch (Throwable t) {
//            throw new MojoExecutionException("Error building generated bom:" + project.getArtifactId(), t);
//        }
//    }

    private String bomKey(Bom bom) {
        return bom.getGroupId() + ":" + bom.getArtifactId() + ":" + bom.getVersion();
    }

    private String dependencyKey(Dependency dependency) {
        Artifact artifact = dependency.getArtifact();
        return artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getExtension() + ":" + artifact.getClassifier();
    }

    private static class VersionInfo implements Comparable<VersionInfo> {

        private Dependency dependency;

        private String bom;

        public VersionInfo(Dependency dependency, String bom) {
            this.dependency = dependency;
            this.bom = bom;
        }

        public Dependency getDependency() {
            return dependency;
        }

        public void setDependency(Dependency dependency) {
            this.dependency = dependency;
        }

        public String getBom() {
            return bom;
        }

        public void setBom(String bom) {
            this.bom = bom;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            VersionInfo that = (VersionInfo) o;

            if (!dependency.getArtifact().getVersion().equals(that.dependency.getArtifact().getVersion())) return false;
            return bom.equals(that.bom);

        }

        @Override
        public int hashCode() {
            int result = dependency.getArtifact().getVersion().hashCode();
            result = 31 * result + bom.hashCode();
            return result;
        }

        @Override
        public int compareTo(VersionInfo versionInfo) {
            return this.toString().compareTo(versionInfo.toString());
        }

        @Override
        public String toString() {
            return dependency.getArtifact().getVersion() + " in " + bom;
        }
    }
}
