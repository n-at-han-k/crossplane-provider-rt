package rt;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import org.openapitools.codegen.CliOption;
import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenOperation;
import org.openapitools.codegen.CodegenProperty;
import org.openapitools.codegen.SupportingFile;
import org.openapitools.codegen.languages.TerraformProviderCodegen;
import org.openapitools.codegen.model.ModelMap;
import org.openapitools.codegen.model.ModelsMap;
import org.openapitools.codegen.model.OperationMap;
import org.openapitools.codegen.model.OperationsMap;
import org.openapitools.codegen.utils.CamelizeOption;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.openapitools.codegen.utils.StringUtils.camelize;
import static org.openapitools.codegen.utils.StringUtils.underscore;

/**
 * A Crossplane provider generator, grouped by RESOURCE instead of by tag.
 *
 * It extends the Terraform generator rather than a Go one because the hard
 * part is not emitting Go -- it is deciding which operations are one resource
 * and which of them is the create, the read, the update and the delete.
 * Upstream's {@code terraform-provider} generator already answers that, and a
 * Crossplane managed resource asks the SAME question: one collection path plus
 * its member path is one Kind, the create body is what a person may write
 * (spec.forProvider), and the read response is what the server answers
 * (status.atProvider).
 *
 * What is replaced is the whole template set and the classification of each
 * attribute, because the two targets disagree about one thing: Terraform has
 * Optional+Computed for a value a person may write and the server may answer,
 * and Crossplane splits that value across spec and status instead.
 *
 * The grouping hook is the same one as
 * github.com/n-at-han-k/crossplane-provider-wso2, and for a sharper reason
 * here: upstream keys its operation map on the tag and then asks
 * {@code CodegenOperation.isRestfulCreate()} which operation is the create.
 * Those helpers assume the create and the collection share a path, and RT
 * does not -- it creates with {@code POST /ticket} and searches with
 * {@code POST /tickets}. Every RT resource comes out of upstream's grouping
 * either empty or doubled, so the shape of the path decides instead.
 */
public class CrossplaneCodegen extends TerraformProviderCodegen {

    public static final String RESOURCE_PATHS = "resourcePaths";
    public static final String PROVIDER_NAME = "providerName";
    public static final String GROUP_NAME = "groupName";
    public static final String API_VERSION = "apiVersion";

    /**
     * Not a comma: --additional-properties is itself comma-separated, so a
     * comma here ends the property rather than separating two paths.
     */
    private static final String SEPARATOR = "[;|\\s]+";

    /** Collection paths to generate; empty means every path in the document. */
    private final Set<String> wanted = new LinkedHashSet<>();

    private String providerName = "rt";
    private String groupName = "rt.crossplane.io";
    private String apiVersion = "v1alpha1";

    public CrossplaneCodegen() {
        super();
        cliOptions.add(new CliOption(RESOURCE_PATHS,
                "Collection paths to generate as managed resources, separated by ';' (default: all)"));
        cliOptions.add(new CliOption(GROUP_NAME, "The CRD API group (default: rt.crossplane.io)"));
        cliOptions.add(new CliOption(API_VERSION, "The CRD API version (default: v1alpha1)"));
    }

    @Override
    public String getName() {
        return "rt-crossplane";
    }

    @Override
    public String getHelp() {
        return "Generates a Crossplane provider, one managed resource per collection path.";
    }

    @Override
    public void processOpts() {
        super.processOpts();

        // Upstream's templates emit a Terraform provider; none of them apply.
        // The directory is ours, so nothing resolves out of the CLI's jar.
        templateDir = "crossplane-provider";
        embeddedTemplateDir = "crossplane-provider";
        apiTemplateFiles.clear();
        modelTemplateFiles.clear();
        supportingFiles.clear();

        if (additionalProperties.containsKey(PROVIDER_NAME)) {
            providerName = additionalProperties.get(PROVIDER_NAME).toString();
        }
        if (additionalProperties.containsKey(GROUP_NAME)) {
            groupName = additionalProperties.get(GROUP_NAME).toString();
        }
        if (additionalProperties.containsKey(API_VERSION)) {
            apiVersion = additionalProperties.get(API_VERSION).toString();
        }
        additionalProperties.put(PROVIDER_NAME, providerName);
        additionalProperties.put(GROUP_NAME, groupName);
        additionalProperties.put(API_VERSION, apiVersion);
        // How the provider is spelled in prose. camelize() of the package name
        // is the fallback and it is often wrong -- `rt` camelises to `Rt`,
        // an abbreviation no document spells that way -- so it can be given.
        if (!additionalProperties.containsKey("providerTitle")) {
            additionalProperties.put("providerTitle", camelize(providerName));
        }

        // Three files per resource, in three different trees. Routed by
        // apiFilename below, since apiTemplateFiles carries only a suffix.
        apiTemplateFiles.put("types.mustache", "_types.go");
        apiTemplateFiles.put("groupversion.mustache", "groupversion_info.go");
        apiTemplateFiles.put("controller.mustache", ".go");

        // The request and response bodies, as Go structs. This is the one
        // thing upstream's Terraform generator emits that a Crossplane
        // provider wants unchanged: internal/clients/<provider>/model_*.go.
        modelTemplateFiles.put("model.mustache", ".go");

        supportingFiles.add(new SupportingFile("gomod.mustache", "", "go.mod"));
        supportingFiles.add(new SupportingFile("main.mustache",
                "cmd" + File.separator + "provider", "main.go"));
        supportingFiles.add(new SupportingFile("client.mustache",
                clientFolder(), "client.go"));
        supportingFiles.add(new SupportingFile("scheme.mustache",
                "apis", providerName + ".go"));
        supportingFiles.add(new SupportingFile("generate_go.mustache", "apis", "generate.go"));
        supportingFiles.add(new SupportingFile("controllers.mustache",
                "internal" + File.separator + "controller", providerName + ".go"));

        // The ProviderConfig: not derived from the document, but its group and
        // its categories are the provider's, so it is generated rather than
        // committed by hand.
        String pcFolder = "apis" + File.separator + apiVersion;
        supportingFiles.add(new SupportingFile("providerconfig_doc.mustache", pcFolder, "doc.go"));
        supportingFiles.add(new SupportingFile("providerconfig_types.mustache", pcFolder, "types.go"));
        supportingFiles.add(new SupportingFile("providerconfig_register.mustache", pcFolder, "register.go"));
        supportingFiles.add(new SupportingFile("config.mustache",
                "internal" + File.separator + "controller" + File.separator + "config", "config.go"));
        supportingFiles.add(new SupportingFile("version.mustache",
                "internal" + File.separator + "version", "version.go"));

        supportingFiles.add(new SupportingFile("Makefile.mustache", "", "Makefile"));

        // The image the xpkg wraps. build/makelib/imagelight.mk expects every
        // image at cluster/images/<name>/, and without it `make build` fails
        // after a clean compile with "No such file" -- a provider that cannot
        // be packaged is not finished, so the scaffold generates this too.
        String imageFolder = "cluster" + File.separator + "images"
                + File.separator + "provider-" + providerName;
        supportingFiles.add(new SupportingFile("image_dockerfile.mustache", imageFolder, "Dockerfile"));
        supportingFiles.add(new SupportingFile("image_makefile.mustache", imageFolder, "Makefile"));
        supportingFiles.add(new SupportingFile("crossplane_yaml.mustache", "package", "crossplane.yaml"));
        supportingFiles.add(new SupportingFile("boilerplate.mustache", "hack", "boilerplate.go.txt"));
        supportingFiles.add(new SupportingFile("gitmodules.mustache", "", ".gitmodules"));

        Object paths = additionalProperties.get(RESOURCE_PATHS);
        if (paths != null && !paths.toString().isEmpty()) {
            Arrays.stream(paths.toString().split(SEPARATOR))
                    .map(String::trim)
                    .filter(path -> !path.isEmpty())
                    .forEach(wanted::add);
        }
    }

    private String clientFolder() {
        return "internal" + File.separator + "clients" + File.separator + providerName;
    }

    /**
     * Each of the three per-resource templates lands in its own tree:
     *
     *   types, groupversion -> apis/<kind>/<version>/
     *   controller          -> internal/controller/<kind>/
     *
     * {@code apiTemplateFiles} carries only a suffix and {@code apiFileFolder()}
     * is one directory for all of them, so the routing happens here.
     */
    @Override
    public String apiFilename(String templateName, String tag) {
        String kind = underscore(toApiName(tag)).toLowerCase(Locale.ROOT);
        String suffix = apiTemplateFiles.get(templateName);

        if ("groupversion.mustache".equals(templateName)) {
            return outputFolder + File.separator + "apis" + File.separator + kind
                    + File.separator + apiVersion + File.separator + suffix;
        }
        if ("types.mustache".equals(templateName)) {
            return outputFolder + File.separator + "apis" + File.separator + kind
                    + File.separator + apiVersion + File.separator + kind + suffix;
        }
        return outputFolder + File.separator + "internal" + File.separator + "controller"
                + File.separator + kind + File.separator + kind + suffix;
    }

    @Override
    public String modelFileFolder() {
        return outputFolder + File.separator + clientFolder();
    }

    @Override
    public String toModelFilename(String name) {
        return "model_" + underscore(name);
    }

    /**
     * The group key is the collection path, so the member operations land with
     * the collection's own. {@code co.baseName} is the collection segment,
     * because that is what {@code pathWithoutBaseName()} strips.
     */
    @Override
    public void addOperationToGroup(String tag, String resourcePath, Operation operation,
                                    CodegenOperation co, Map<String, List<CodegenOperation>> operations) {
        String collection = collectionOf(resourcePath);

        // RT POSTs both to create and to SEARCH -- POST /ticket creates one,
        // POST /tickets searches -- and the two are told apart by what they
        // answer, not by how the path is spelled. A create answers 201, a
        // search answers 200. That reading survives the places where the
        // spelling does not: `POST /lifecycles` is a create on a plural path,
        // and `POST /customfields` is a search on one.
        //
        // What is kept is everything a managed resource actually calls:
        //
        //   a member path                GET read, PUT update, DELETE delete
        //   POST answering 201           the create
        //   PUT on a collection          RT's idempotent "set these" --
        //                                /user/{idOrName}/groups
        //   DELETE on a collection       the inverse of that set
        //
        // and what is dropped is a GET on a collection (a list, which nothing
        // calls) and a POST that answers 200 (a search, or an action endpoint
        // like /lifecycle/{name}/validate).
        String method = co.httpMethod.toUpperCase(Locale.ROOT);
        boolean member = isMember(collection, resourcePath);
        boolean set = "PUT".equals(method) || "PATCH".equals(method);
        boolean create = "POST".equals(method) && answers(operation, "201");
        boolean clear = "DELETE".equals(method);

        if (!member && !set && !create && !clear) {
            return;
        }

        if (!describesAResource(collection)) {
            return;
        }

        List<CodegenOperation> group =
                operations.computeIfAbsent(canonicalCollection(collection), key -> new ArrayList<>());

        // An operation carrying two tags is offered once per tag; here both
        // offers name the same group, so the second one is a duplicate.
        if (group.stream().anyMatch(existing -> existing.operationId.equals(co.operationId))) {
            return;
        }

        group.add(co);
        co.baseName = lastSegment(collection);
    }

    /**
     * What survives grouping, and under which key.
     *
     * Two things cannot be decided from one operation alone, and the whole
     * document is right here -- {@code openAPI} is set before any of this
     * runs -- so both are answered by looking at it.
     *
     * <p><b>A group that describes nothing.</b> RT revokes a right with
     * {@code DELETE /queue/{id}/rights/{right}/group/{id}} and removes one
     * member with {@code DELETE /group/{id}/member/{id}}. Each looks like a
     * member path, so each would become a Kind that can only be deleted --
     * never created, never read, nothing for Crossplane to own. A collection
     * with neither a create nor a read is a verb spelled as a path.
     *
     * <p><b>Two paths, one resource.</b> RT creates a lifecycle at
     * {@code POST /lifecycles} and addresses it at {@code /lifecycle/{name}}
     * ever after, and both camelise to {@code Lifecycle}. Left alone they are
     * two groups writing one set of files, the second silently overwriting
     * the first with half a resource. Both are keyed on the collection that
     * owns the member path.
     *
     * <p>A collection that describes nothing is never a candidate to be
     * keyed on, which is what keeps the lone revoke DELETE at
     * {@code /group/{id}/member} from swallowing the membership set at
     * {@code /group/{id}/members}.
     */
    private boolean describesAResource(String collection) {
        if (openAPI == null || openAPI.getPaths() == null) {
            return true;
        }

        for (Map.Entry<String, PathItem> entry : openAPI.getPaths().entrySet()) {
            String path = entry.getKey();

            if (!collectionOf(path).equals(collection)) {
                continue;
            }

            for (Map.Entry<PathItem.HttpMethod, Operation> described
                    : entry.getValue().readOperationsMap().entrySet()) {
                String method = described.getKey().name();
                boolean member = isMember(collection, path);

                if (member && "GET".equals(method)) {
                    return true;
                }
                if (!member && ("PUT".equals(method) || "PATCH".equals(method)
                        || ("POST".equals(method) && answers(described.getValue(), "201")))) {
                    return true;
                }
            }
        }

        return false;
    }

    /** The collection every group of this Kind is keyed on. */
    private String canonicalCollection(String collection) {
        if (openAPI == null || openAPI.getPaths() == null) {
            return collection;
        }

        String kind = toApiName(collection);
        String canonical = collection;

        for (String path : openAPI.getPaths().keySet()) {
            String other = collectionOf(path);

            if (other.equals(canonical) || !toApiName(other).equals(kind)
                    || !describesAResource(other)) {
                continue;
            }

            // The member path is what the resource is addressed by, so its
            // collection is the one to key on; failing that, take the same
            // one every time rather than whichever was seen first.
            boolean canonicalAddresses = addressedByAMember(canonical);
            boolean otherAddresses = addressedByAMember(other);

            if (otherAddresses && !canonicalAddresses) {
                canonical = other;
            } else if (otherAddresses == canonicalAddresses && other.compareTo(canonical) < 0) {
                canonical = other;
            }
        }

        return canonical;
    }

    private boolean addressedByAMember(String collection) {
        return openAPI.getPaths().keySet().stream()
                .anyMatch(path -> collectionOf(path).equals(collection) && isMember(collection, path));
    }

    /**
     * The collection a group is about: the one its member path hangs off,
     * because that is what the resource is addressed by. With no member path
     * -- a set, like /group/{id}/members -- the shortest path will do, since
     * they are all the same one.
     */
    private String collectionOf(List<CodegenOperation> group) {
        String shortest = null;

        for (CodegenOperation op : group) {
            String collection = collectionOf(op.path);

            if (!collection.equals(op.path)) {
                return collection;
            }
            if (shortest == null || collection.length() < shortest.length()) {
                shortest = collection;
            }
        }

        return shortest == null ? "" : shortest;
    }

    /** Whether a described operation documents this response code. */
    private boolean answers(Operation operation, String code) {
        return operation.getResponses() != null && operation.getResponses().containsKey(code);
    }

    /** Whether an operation documents this response code. */
    private boolean answers(CodegenOperation op, String code) {
        return op.responses != null
                && op.responses.stream().anyMatch(response -> code.equals(response.code));
    }

    /**
     * Which operation is the create, the read, the update, the delete.
     *
     * Upstream asks {@code CodegenOperation.isRestfulCreate()} and friends,
     * and those cannot answer for a NESTED resource: {@code isMemberPath()}
     * opens with {@code if (pathParams.size() != 1) return false}, so
     * a nested member path with two path params looks like nothing at all,
     * and the whole resource comes out empty.
     *
     * The shape of the path already says it. This group IS a collection path
     * and its member path, so an operation on the collection is the create or
     * the list, and one on the member is the read, the update or the delete.
     * Marked as vendor extensions, which is upstream's own first-pass hook.
     *
     * Where a member path offers both PUT and PATCH the update is the PUT: a
     * PATCH body is a list of patch operations while the controller sends a
     * whole model.
     */
    @Override
    public OperationsMap postProcessOperationsWithModels(OperationsMap objs, List<ModelMap> allModels) {
        List<CodegenOperation> group = objs.getOperations().getOperation();
        String collection = collectionOf(group);

        CodegenOperation update = null;
        boolean deleteOnCollection = false;

        for (CodegenOperation op : group) {
            boolean member = isMember(collection, op.path);
            String method = op.httpMethod.toUpperCase(Locale.ROOT);

            if (member) {
                if ("GET".equals(method)) {
                    op.vendorExtensions.put("x-terraform-is-read", true);
                } else if ("DELETE".equals(method)) {
                    op.vendorExtensions.put("x-terraform-is-delete", true);
                } else if ("PUT".equals(method) || ("PATCH".equals(method) && update == null)) {
                    update = "PUT".equals(method) || update == null ? op : update;
                }
            } else if ("DELETE".equals(method)) {
                // The inverse of a set: DELETE /group/{id}/members empties
                // what PUT /group/{id}/members filled. It takes no id of its
                // own, which is what deleteIsSet below tells the template.
                op.vendorExtensions.put("x-terraform-is-delete", true);
                deleteOnCollection = true;
            } else if (!"GET".equals(method)) {
                // A POST answering 201, or a set's PUT. Its path need not be
                // the collection: RT creates a lifecycle at /lifecycles and
                // addresses it at /lifecycle/{name} ever after.
                op.vendorExtensions.put("x-terraform-is-create", true);
            }
        }

        if (update != null) {
            update.vendorExtensions.put("x-terraform-is-update", true);
        }

        OperationsMap processed = super.postProcessOperationsWithModels(objs, allModels);
        OperationMap operations = processed.getOperations();

        // Upstream leaves the CREATE path's parameters spelled `{applicationId}`
        // while converting the read, update and delete paths to `%v` -- it has
        // never had to interpolate a create, because the only create it can
        // recognise is on a top-level collection that takes no parameters. A
        // nested create then goes out to a URL with a literal `{idOrName}` in
        // it, which `fmt.Sprintf` COMPILES (with a trailing %!(EXTRA)) and the
        // server answers 404 for. Every path gets the same treatment here.
        for (String key : new String[] {"createPath", "readPath", "updatePath", "deletePath"}) {
            Object path = operations.get(key);
            if (path != null) {
                operations.put(key, path.toString().replaceAll("\\{[^}]*\\}", "%v"));
            }
        }

        split(operations, allModels, collection);

        // RT's membership endpoints are a SET, not a create: `PUT
        // /user/{idOrName}/groups` replaces that user's memberships and
        // answers a message rather than an identifier, and there is no
        // Location header either. The resource IS "this user's groups", so
        // the owning id is what the external name has to be -- without this
        // the create refuses to record an empty one and the resource never
        // reconciles at all.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> owning = (List<Map<String, Object>>) operations.get("parents");
        String createMethod = String.valueOf(operations.get("createMethod"));
        boolean isSet = ("PUT".equals(createMethod) || "PATCH".equals(createMethod))
                && owning != null && !owning.isEmpty();

        operations.put("isSet", isSet);
        // A delete on the collection takes the owning ids and nothing else --
        // there is no id of its own to append, because the resource is the
        // whole set.
        operations.put("deleteIsSet", deleteOnCollection);
        if (isSet) {
            operations.put("setIdField", owning.get(0).get("goName"));
        }

        // From the COLLECTION PATH, not from resourceClassName: upstream
        // derives that one from the response model's name, which does not
        // always match the file the resource is written to, and a package
        // that does not exist is a build that does not run.
        String kind = toApiName(collection);
        operations.put("kind", kind);
        operations.put("kindLower", kind.toLowerCase(Locale.ROOT));
        operations.put("kindPackage", underscore(kind).toLowerCase(Locale.ROOT));
        operations.put("kindCamel", camelize(kind, CamelizeOption.LOWERCASE_FIRST_LETTER));

        // fmt is imported only to interpolate an id into a member path, so a
        // resource whose paths take no parameters must not import it.
        operations.put("needsFmt", truthy(operations.get("readHasPathParams"))
                || truthy(operations.get("updateHasPathParams"))
                || truthy(operations.get("deleteHasPathParams"))
                // A nested collection interpolates its owning ids into the
                // CREATE path too.
                || truthy(operations.get("hasParents")));

        return processed;
    }

    /**
     * Terraform has ONE schema and marks a value Optional+Computed when a
     * person may write it and the server may also answer it. Crossplane has
     * two: spec.forProvider is what a person writes and status.atProvider is
     * what the server answers, and the same field appearing in both is normal
     * rather than a conflict.
     *
     * So the split is simply: the create request body is
     * {@code <Kind>Parameters}, and the read response is
     * {@code <Kind>Observation}. Nothing has to be guessed about which of them
     * a field belongs to, which is the one thing the Terraform generator could
     * not do -- it had to infer Computed from "the response has it and the
     * request does not", and got `id`, `created` and `version` asked for in
     * configuration when the document never says readOnly -- and RT's
     * document never does.
     *
     * A parameter the create body requires is Required; everything else is
     * optional and carries omitempty. Anything that is not a scalar becomes a
     * JSON string for now, as in the Terraform provider -- see the README.
     */
    private void split(OperationMap operations, List<ModelMap> allModels, String collection) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attributes =
                (List<Map<String, Object>>) operations.get("tfAttributes");

        CodegenModel request = modelNamed(allModels, (String) operations.get("requestModel"));

        List<Map<String, Object>> parameters = new ArrayList<>();
        List<Map<String, Object>> observations = new ArrayList<>();

        // What the READ answers, by name, so a parameter can be told whether
        // there is anything to diff it against.
        Map<String, Map<String, Object>> answered = new LinkedHashMap<>();
        if (attributes != null) {
            for (Map<String, Object> attribute : attributes) {
                answered.put(String.valueOf(attribute.get("name")).toLowerCase(Locale.ROOT), attribute);
            }
        }

        if (request != null) {
            for (CodegenProperty property : request.vars) {
                Map<String, Object> field = field(property.baseName, property.dataType,
                        property.description, property.required);
                field.put("isSensitive", property.isWriteOnly
                        || property.baseName.toLowerCase(Locale.ROOT).contains("password")
                        || property.baseName.toLowerCase(Locale.ROOT).contains("secret"));

                // Comparable only where the server answers the field under the
                // same name AND the same shape. A user goes out with a
                // `Password` and comes back without one, so diffing every
                // field a create accepts would report drift forever.
                Map<String, Object> answer = answered.get(property.baseName.toLowerCase(Locale.ROOT));
                boolean sameShape = answer != null
                        && String.valueOf(answer.get("goType")).equals(property.dataType);
                // A secret the server never echoes cannot be diffed either.
                field.put("comparable", sameShape && !Boolean.TRUE.equals(field.get("isSensitive")));

                parameters.add(field);
            }
        }

        if (attributes != null) {
            for (Map<String, Object> attribute : attributes) {
                String name = String.valueOf(attribute.get("name"));
                // Everything the server answers is observable, including the
                // fields a person also writes: status.atProvider is what IS,
                // not what was asked for, and the controller diffs the two.
                observations.add(field(name, String.valueOf(attribute.get("goType")),
                        String.valueOf(attribute.get("description")), false));
            }
        }

        // A document can describe an operation with no body at all -- a POST
        // that takes nothing, a GET whose 200 has no schema. Upstream still
        // reports hasCreate and hasRead for those, and the templates would
        // then spell `rt.` where a type name belongs. So what the templates
        // are told is not "is there an operation" but "is there a TYPE".
        String requestModel = String.valueOf(operations.get("requestModel"));
        String responseModel = String.valueOf(operations.get("responseModel"));
        boolean hasRequestModel = request != null && !requestModel.isEmpty() && !"null".equals(requestModel);
        boolean hasResponseModel = !responseModel.isEmpty() && !"null".equals(responseModel);

        // An operation can answer something that is not a struct at all: a
        // LIST (RT's membership PUT answers one message per group it
        // touched) or a MAP (a lifecycle's status maps are keyed by
        // "<from> -> <to>"). There is no single object to project onto
        // status.atProvider, `rt.[]Thing` and `rt.map[string]string` are not
        // type names, and neither is state anyway -- so both are treated as
        // no response rather than emitted as something that will not
        // compile.
        if (isNotAStruct(responseModel)) {
            hasResponseModel = false;
        }
        if (isNotAStruct(requestModel)) {
            hasRequestModel = false;
        }

        operations.put("hasRequestModel", hasRequestModel);
        operations.put("hasResponseModel", hasResponseModel);

        // Nothing to unmarshal a read into is the same as having no read: the
        // controller cannot observe anything either way, and saying so here
        // keeps the branch out of every template.
        if (!hasResponseModel) {
            operations.put("hasRead", false);
        }

        // The identifier has to exist ON THE RESPONSE MODEL, not merely be
        // named: a response model that carries no id makes `created.Id` a
        // line that does not compile.
        CodegenModel response = modelNamed(allModels, hasResponseModel ? responseModel : null);
        String idField = String.valueOf(operations.get("idFieldExported"));
        boolean hasID = response != null && !idField.isEmpty() && !"null".equals(idField)
                && response.vars.stream().anyMatch(v -> camelize(v.baseName).equals(idField));
        operations.put("hasID", hasID);

        // RT's delete for most objects is a DISABLE: the object stays, with
        // Disabled "1". A read therefore still finds it after a successful
        // delete, Crossplane deletes again, and the resource never finalises
        // -- sixteen deletes in eleven minutes and a CR that cannot be
        // removed. Where the read answers Disabled, that is what "gone"
        // looks like, and only while the resource is being deleted: a
        // disabled object that nobody asked to delete still exists, and
        // reporting otherwise would have Crossplane create a second one.
        operations.put("disabledIsGone", observations.stream()
                .anyMatch(f -> "Disabled".equals(f.get("goName"))));

        operations.put("comparables",
                parameters.stream().filter(f -> Boolean.TRUE.equals(f.get("comparable"))).toList());

        // The owning ids come FIRST in the struct and in every path, because
        // that is the order the path spells them.
        List<Map<String, Object>> parents = parents(collection);
        Set<String> named = new HashSet<>();
        for (Map<String, Object> parent : parents) {
            named.add(String.valueOf(parent.get("goName")));
        }
        // A request body that already carries the owning id wins: it is the
        // document's own spelling, and two struct fields cannot share a name.
        parameters.removeIf(f -> named.contains(String.valueOf(f.get("goName"))));
        parameters.addAll(0, parents);

        operations.put("parents", parents);
        operations.put("hasParents", !parents.isEmpty());
        operations.put("parameters", parameters);
        operations.put("observations", observations);
        operations.put("hasParameters", !parameters.isEmpty());
        operations.put("hasObservations", !observations.isEmpty());
        operations.put("anyJson", parameters.stream().anyMatch(f -> Boolean.TRUE.equals(f.get("isJson")))
                || observations.stream().anyMatch(f -> Boolean.TRUE.equals(f.get("isJson"))));
    }

    /**
     * The path parameters of the COLLECTION path, in order: everything that
     * has to be known before this resource can even be addressed.
     *
     * {@code /user/{idOrName}/groups} is owned by a user, so a UserGroup
     * carries a required, immutable {@code idOrName} -- there is nowhere else
     * for the controller to get it, and changing it would address a different
     * user's memberships rather than modify these.
     */
    private List<Map<String, Object>> parents(String collection) {
        List<Map<String, Object>> parents = new ArrayList<>();

        for (String segment : collection.split("/")) {
            if (!segment.startsWith("{") || !segment.endsWith("}")) {
                continue;
            }

            String param = segment.substring(1, segment.length() - 1).replace('-', '_');
            Map<String, Object> field = field(camelize(param, CamelizeOption.LOWERCASE_FIRST_LETTER),
                    "string", "The identifier of the owning resource, from the path.", true);
            field.put("isParent", true);
            field.put("comparable", false);
            parents.add(field);
        }

        return parents;
    }

    /** One field of a Parameters or an Observation struct. */
    private Map<String, Object> field(String baseName, String dataType, String description, boolean required) {
        Map<String, Object> field = new HashMap<>();

        boolean scalar = isScalar(dataType) || isIdentifier(baseName);

        field.put("name", baseName);
        // The JSON tag is the wire name VERBATIM, so the CRD field, the Go
        // struct and the request body all spell it the same way and nothing
        // needs a translation layer.
        field.put("jsonName", baseName);
        field.put("goName", camelize(baseName));
        String goType = scalar ? goType(dataType) : "string";

        field.put("goType", goType);
        // The client struct keeps the document's own width; a CRD does not,
        // because Kubernetes has no int32. So the two disagree for exactly the
        // narrow numbers, and the controller converts rather than the schema
        // lying about what the API takes.
        // An identifier is RTID on the client and a plain string in the CRD:
        // `string(in.Id)` converts, and Kubernetes never sees a field whose
        // type depends on which endpoint answered.
        if (isIdentifier(baseName)) {
            goType = "string";
            field.put("goType", goType);
            field.put("clientType", "RTID");
            field.put("needsCast", true);
        } else {
            field.put("clientType", scalar ? dataType : "");
            field.put("needsCast", scalar && !goType.equals(dataType));
        }
        // What "the person did not set this" looks like for this type, so
        // upToDate can tell an unset optional field from a difference. RT
        // answers a queue's Lifecycle as "default" whether or not anyone
        // asked for one, and without this every such field is permanent
        // drift and the controller updates on every single reconcile.
        //
        // A bool has no spare value to mean unset, so it is always compared.
        switch (goType) {
            case "string":  field.put("zeroCheck", "!= \"\""); break;
            case "int64":
            case "float64": field.put("zeroCheck", "!= 0"); break;
            default:        break;
        }

        field.put("description", description == null || "null".equals(description) ? "" : description);
        field.put("isRequired", required);
        field.put("isJson", !scalar);
        field.put("isSensitive", false);

        return field;
    }

    private boolean truthy(Object value) {
        return Boolean.TRUE.equals(value) || "true".equals(String.valueOf(value));
    }

    private boolean isScalar(String dataType) {
        switch (dataType == null ? "" : dataType) {
            case "string": case "bool":
            case "int": case "int32": case "int64":
            case "float32": case "float64":
                return true;
            default:
                return false;
        }
    }

    private String goType(String dataType) {
        switch (dataType == null ? "" : dataType) {
            case "int": case "int32": case "int64": return "int64";
            case "float32": case "float64": return "float64";
            case "bool": return "bool";
            default: return dataType;
        }
    }

    private CodegenModel modelNamed(List<ModelMap> allModels, String classname) {
        if (classname == null) {
            return null;
        }
        for (ModelMap map : allModels) {
            if (classname.equals(map.getModel().classname)) {
                return map.getModel();
            }
        }
        return null;
    }

    /**
     * Every field omitempty: a Crossplane parameter that is optional and unset
     * is the Go zero value, and without omitempty that goes out as
     * {@code "status": ""} -- a field the create endpoint never asked for and
     * can refuse over.
     */
    @Override
    public ModelsMap postProcessModels(ModelsMap objs) {
        ModelsMap processed = super.postProcessModels(objs);

        for (ModelMap map : processed.getModels()) {
            CodegenModel model = map.getModel();

            // A schema with no fields of its own OR inherited is not an
            // object this client can hold: `ticketLink` is `anyOf: [integer,
            // array]` and the parameter schemas are `oneOf: [integer,
            // string]`. Rendered as an empty struct, unmarshalling a number
            // into it fails outright -- which is the same failure that
            // duplicated seven assets, one level down.
            model.vendorExtensions.put("x-opaque",
                    !model.isEnum && !model.isAlias
                            && (model.allVars == null || model.allVars.isEmpty()));

            // vars AND allVars: the inherited half of an `allOf` is a
            // SEPARATE CodegenProperty instance, and the template renders
            // allVars. Rewriting only vars leaves those without a json tag
            // and with the types this fixes up below.
            List<CodegenProperty> properties = new ArrayList<>(model.vars);
            if (model.allVars != null) {
                properties.addAll(model.allVars);
            }

            for (CodegenProperty property : properties) {
                // OpenAPI 3.1 lets a schema carry a type AND an `anyOf` that
                // only narrows it: RT's EmailAddress is `type: string` with
                // an anyOf of {format: email, maxLength: 0}, meaning "an
                // address, or empty". openapi-generator names that
                // composition `AnyOf` and emits it as the Go type, which is
                // not a type and does not compile. The declared type is still
                // on the property, so it is used; a genuine union -- one that
                // declares no type of its own -- has nothing better than
                // `interface{}` and travels as JSON.
                if (property.dataType != null
                        && (property.dataType.startsWith("AnyOf") || property.dataType.startsWith("OneOf"))) {
                    property.dataType = unionType(property);
                } else if (isIdentifier(property.baseName)) {
                    // See RTID in client.go: RT types its identifiers
                    // inconsistently -- a number in one response, a quoted
                    // string in the next, both in one array -- and every
                    // model that commits to one of them fails to parse the
                    // other. This is a rule about the API, not a list of
                    // exceptions: RT spells every identifier `id`.
                    property.dataType = "RTID";
                } else if (isGenuineUnion(property)) {
                    // A composition with BRANCHES, as opposed to the
                    // validation-only kind above: RT answers a queue's
                    // `_hyperlinks[].id` as the number 3 and the same
                    // object's `TicketCustomFields[].id` as the string "2",
                    // so the document says `anyOf: [integer, string]`.
                    // openapi-generator collapses that to whichever branch it
                    // saw last, and the client then fails to parse half the
                    // responses the API actually sends.
                    property.dataType = "interface{}";
                }

                property.vendorExtensions.put("x-go-datatag",
                        " `json:\"" + property.baseName + ",omitempty\"`");
            }
        }

        return processed;
    }

    /** RT spells every identifier `id`, and types it however it feels. */
    private boolean isIdentifier(String baseName) {
        return "id".equalsIgnoreCase(baseName);
    }

    /** A composition with branches that disagree, not one that only narrows. */
    private boolean isGenuineUnion(CodegenProperty property) {
        if (property.getComposedSchemas() == null) {
            return false;
        }

        List<CodegenProperty> anyOf = property.getComposedSchemas().getAnyOf();
        List<CodegenProperty> oneOf = property.getComposedSchemas().getOneOf();

        return (anyOf != null && anyOf.size() > 1) || (oneOf != null && oneOf.size() > 1);
    }

    /** The declared type behind a composition, or {@code interface{}}. */
    private String unionType(CodegenProperty property) {
        if (property.isString) {
            return "string";
        }
        if (property.isInteger || property.isLong) {
            return "int64";
        }
        if (property.isNumber || property.isFloat || property.isDouble) {
            return "float64";
        }
        if (property.isBoolean) {
            return "bool";
        }
        return "interface{}";
    }

    /**
     * A field whose type disagrees across a union's branches holds neither.
     *
     * openapi-generator flattens an inline {@code anyOf} into ONE struct and
     * gives each field the type of whichever branch it saw last. RT sends a
     * ticket's {@code _hyperlinks} as one array of both kinds at once:
     *
     * <pre>
     *   {"ref":"self","id":1}          &lt;- a number
     *   {"ref":"customfield","id":"2"} &lt;- a string
     * </pre>
     *
     * So the flattened {@code Id} is a string, and unmarshalling the array
     * fails on the first element -- which is what the API answers to a read
     * of a queue this provider just created. Whichever branch had won, half
     * the responses would fail.
     *
     * This runs over ALL models, because the branches are models of their own
     * and a single model cannot see them.
     */
    @Override
    public Map<String, ModelsMap> postProcessAllModels(Map<String, ModelsMap> models) {
        Map<String, ModelsMap> processed = super.postProcessAllModels(models);

        Map<String, CodegenModel> byName = new LinkedHashMap<>();
        for (ModelsMap entry : processed.values()) {
            for (ModelMap map : entry.getModels()) {
                byName.put(map.getModel().classname, map.getModel());
            }
        }

        for (CodegenModel model : byName.values()) {
            Set<String> branches = new LinkedHashSet<>();
            if (model.anyOf != null) {
                branches.addAll(model.anyOf);
            }
            if (model.oneOf != null) {
                branches.addAll(model.oneOf);
            }
            if (branches.isEmpty()) {
                continue;
            }

            List<CodegenProperty> properties = new ArrayList<>(model.vars);
            if (model.allVars != null) {
                properties.addAll(model.allVars);
            }

            for (CodegenProperty property : properties) {
                Set<String> types = new LinkedHashSet<>();

                for (String branch : branches) {
                    CodegenModel source = byName.get(branch);
                    if (source == null) {
                        continue;
                    }
                    source.vars.stream()
                            .filter(candidate -> candidate.baseName.equals(property.baseName))
                            .forEach(candidate -> types.add(candidate.dataType));
                }

                if (types.size() > 1 && !isIdentifier(property.baseName)) {
                    property.dataType = "interface{}";
                }
            }
        }

        return processed;
    }

    /**
     * The Kind, from EVERY literal segment of the collection path:
     *
     *   /ticket                        -> Ticket
     *   /group/{id}/members            -> GroupMember
     *
     * The leaf alone would do for a top-level path, and does not once the
     * whole document is generated: two parents can each own an `owners`
     * collection, and two Kinds named Owner would be one package and one CRD
     * silently overwriting the other.
     */
    @Override
    public String toApiName(String name) {
        String[] segments = name.split("/");
        StringBuilder kind = new StringBuilder();

        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];

            if (segment.isEmpty() || segment.startsWith("{")) {
                continue;
            }

            // Singular where this segment names ONE of something -- the last
            // segment, which is the resource itself, and any segment followed
            // by its identifier. Plural otherwise, which is what keeps an
            // endpoint OF a collection apart from the same-named endpoint of
            // one member of it -- without that they are one Kind, and one CRD
            // silently overwrites the other.
            boolean one = i == segments.length - 1
                    || (i + 1 < segments.length && segments[i + 1].startsWith("{"));

            String word = segment.replace('-', '_');
            kind.append(camelize(one ? singular(word) : word));
        }

        return kind.length() == 0 ? "Resource" : kind.toString();
    }

    @Override
    public String toApiFilename(String name) {
        return underscore(toApiName(name));
    }

    /** A trailing {@code /{param}} is the member of a collection, not a collection. */
    private String collectionOf(String path) {
        // A document that spells a collection with a trailing slash in one
        // place and without in another means the same collection both times;
        // without this they are two groups writing one file.
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        int cut = path.lastIndexOf('/');

        if (cut > 0 && path.endsWith("}") && path.startsWith("{", cut + 1)) {
            return path.substring(0, cut);
        }
        return path;
    }

    private boolean isMember(String collection, String path) {
        return collectionOf(path).equals(collection) && !path.equals(collection);
    }

    private String lastSegment(String path) {
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return trimmed.substring(trimmed.lastIndexOf('/') + 1);
    }

    // ponytail: a trailing "s" is the whole pluralisation rule, and here it
    // decides which operations even exist -- a plural path is a search and is
    // dropped. RT spells tickets, users, groups, queues, customfields and
    // members, all of which it gets right; a document spelling "addresses" or
    // "people" wants a real inflector.
    /** A Go type that cannot be qualified with a package name. */
    private boolean isNotAStruct(String dataType) {
        return dataType.startsWith("[") || dataType.startsWith("map[")
                || dataType.startsWith("interface{");
    }

    /** A segment naming several of something -- the search paths, in RT. */
    private boolean isPlural(String name) {
        return !singular(name).equals(name);
    }

    private String singular(String name) {
        String lower = name.toLowerCase(Locale.ROOT);

        // "status" is not a plural of "statu", and neither is "analysis" of
        // "analysi" -- a trailing "s" after a vowel that is itself part of the
        // word is the common way a naive rule goes wrong, and this API spells
        // both /status and /metadata.
        if (lower.endsWith("ss") || lower.endsWith("us") || lower.endsWith("is")) {
            return name;
        }
        if (lower.endsWith("s")) {
            return name.substring(0, name.length() - 1);
        }
        return name;
    }
}
