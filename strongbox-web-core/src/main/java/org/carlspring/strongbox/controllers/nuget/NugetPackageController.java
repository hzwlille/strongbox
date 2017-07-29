package org.carlspring.strongbox.controllers.nuget;

import org.carlspring.strongbox.controllers.BaseArtifactController;
import org.carlspring.strongbox.event.artifact.ArtifactEventListenerRegistry;
import org.carlspring.strongbox.io.ArtifactInputStream;
import org.carlspring.strongbox.io.ReplacingInputStream;
import org.carlspring.strongbox.security.exceptions.SecurityTokenException;
import org.carlspring.strongbox.services.ArtifactManagementService;
import org.carlspring.strongbox.storage.Storage;
import org.carlspring.strongbox.storage.repository.Repository;
import org.carlspring.strongbox.users.service.UserService;
import org.carlspring.strongbox.utils.ArtifactControllerHelper;

import javax.inject.Inject;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.MediaType;
import javax.xml.bind.JAXBException;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.apache.commons.fileupload.MultipartStream;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import ru.aristar.jnuget.QueryExecutor;
import ru.aristar.jnuget.files.NugetFormatException;
import ru.aristar.jnuget.files.Nupkg;
import ru.aristar.jnuget.files.TempNupkgFile;
import ru.aristar.jnuget.query.Expression;
import ru.aristar.jnuget.query.Lexer;
import ru.aristar.jnuget.query.QueryLexer;
import ru.aristar.jnuget.rss.NuPkgToRssTransformer;
import ru.aristar.jnuget.rss.PackageFeed;
import ru.aristar.jnuget.sources.PackageSource;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * This Controller used to handle Nuget requests.
 * 
 * @author Sergey Bespalov
 *
 */
@RestController
@RequestMapping(path = NugetPackageController.ROOT_CONTEXT, headers = "user-agent=NuGet/*")
public class NugetPackageController extends BaseArtifactController
{

    private static final Logger logger = LoggerFactory.getLogger(NugetPackageController.class);

    public final static String ROOT_CONTEXT = "/storages";

    @Inject
    private ArtifactManagementService nugetArtifactManagementService;

    @Inject
    private UserService userService;

    @Inject
    protected ArtifactEventListenerRegistry artifactEventListenerRegistry;


    @Inject
    private NugetSearchPackageSource packageSource;
    
    @RequestMapping(path = { "{storageId}/{repositoryId}/Search()/$count" }, method = RequestMethod.GET, produces = MediaType.TEXT_PLAIN)
    public ResponseEntity<String> countPackages(@RequestParam(name = "$filter", required = false) String filter,
                                                @RequestParam(name = "searchTerm", required = false) String searchTerm,
                                                @RequestParam(name = "targetFramework", required = false) String targetFramework)
    {
        return new ResponseEntity<>("1", HttpStatus.OK);
    }

    @RequestMapping(path = { "{storageId}/{repositoryId}/{searchCommandName:(?:Packages(?:\\(\\))?|Search\\(\\))}" }, method = RequestMethod.GET, produces = MediaType.APPLICATION_XML)
    public ResponseEntity<?> searchPackages(@ApiParam(value = "The storageId", required = true) @PathVariable(name = "storageId") String storageId,
                                            @ApiParam(value = "The repositoryId", required = true) @PathVariable(name = "repositoryId") String repositoryId,
                                            @PathVariable(name = "searchCommandName") String searchCommandName,
                                            @RequestParam(name = "$filter", required = true) String filter,
                                            @RequestParam(name = "$orderby", required = false, defaultValue = "id") String orderBy,
                                            @RequestParam(name = "$skip", required = false, defaultValue = "0") int skip,
                                            @RequestParam(name = "$top", required = false, defaultValue = "-1") int top,
                                            @RequestParam(name = "searchTerm", required = false) String searchTerm,
                                            @RequestParam(name = "targetFramework", required = false) String targetFramework)
        throws JAXBException
    {
        Lexer queryLexer = new QueryLexer();
        Expression expression;
        try
        {
            expression = queryLexer.parse(filter);
        }
        catch (NugetFormatException e)
        {
            return ResponseEntity.badRequest().build();
        }

        packageSource.setStorageId(storageId);
        packageSource.setRepositoryId(repositoryId);
        packageSource.setSearchTerm(searchTerm);
        Collection<? extends Nupkg> files = getPackages(packageSource, filter, normaliseSearchTerm(searchTerm),
                                                        targetFramework);

        String feedId = getFeedUri(((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest(),
                                   storageId,
                                   repositoryId);

        NuPkgToRssTransformer toRssTransformer = new NuPkgToRssTransformer(feedId);
        PackageFeed feed = toRssTransformer.transform(files, orderBy, skip, top);

        ByteArrayOutputStream rssResultStream = new ByteArrayOutputStream();
        feed.writeXml(rssResultStream);

        return new ResponseEntity<>(new String(rssResultStream.toByteArray(), Charset.forName("UTF-8")), HttpStatus.OK);
    }

    private String getFeedUri(HttpServletRequest request, String storageId, String repositoryId)
    {
        return String.format("%s://%s:%s%s/storages/%s/%s/", request.getScheme(), request.getServerName(),
                             request.getServerPort(),
                             request.getContextPath(), storageId, repositoryId);
    }

    private Collection<? extends Nupkg> getPackages(PackageSource<Nupkg> packageSource,
                                                    String filter,
                                                    String searchTerm,
                                                    String targetFramework)
    {
        QueryExecutor queryExecutor = new QueryExecutor();
        Collection<? extends Nupkg> files = queryExecutor.execQuery(packageSource, filter, searchTerm, targetFramework);
        return files;
    }

    @ApiOperation(value = "Used to get storage metadata")
    @ApiResponses(value = { @ApiResponse(code = HttpURLConnection.HTTP_OK, message = "The metadata was downloaded successfully."),
                            @ApiResponse(code = HttpURLConnection.HTTP_INTERNAL_ERROR, message = "An error occurred.") })
    @RequestMapping(path = { "{storageId}/{repositoryId}/$metadata" }, method = RequestMethod.GET, produces = MediaType.APPLICATION_XML)
    public ResponseEntity<Resource> getMetadata()
        throws IOException
    {
        InputStream inputStream = NugetPackageController.class.getResourceAsStream("/metadata.xml");
        return new ResponseEntity<>(new InputStreamResource(inputStream), HttpStatus.OK);
    }

    /**
     * This method is used to check storage availability.<br>
     * For example NuGet pings the root without credentials to determine if the repository is healthy. If this receives
     * a 401 response then NuGet will prompt for authentication.
     * 
     * @return
     */
    @ApiOperation(value = "Used to check storage availability")
    @ApiResponses(value = { @ApiResponse(code = HttpURLConnection.HTTP_OK, message = "Storage available."),
                            @ApiResponse(code = HttpURLConnection.HTTP_UNAUTHORIZED, message = "Storage requires authorization.") })
    @RequestMapping(path = { "{storageId}/{repositoryId}", "greet" }, method = RequestMethod.GET)
    public ResponseEntity greet()
    {
        return new ResponseEntity<>("success", HttpStatus.OK);
    }

    @ApiOperation(value = "Used to deploy a package")
    @ApiResponses(value = { @ApiResponse(code = HttpURLConnection.HTTP_OK, message = "The package was deployed successfully."),
                            @ApiResponse(code = HttpURLConnection.HTTP_INTERNAL_ERROR, message = "An error occurred.") })
    @PreAuthorize("hasAuthority('ARTIFACTS_DEPLOY')")
    @RequestMapping(path = "{storageId}/{repositoryId}/", method = RequestMethod.PUT, consumes = MediaType.MULTIPART_FORM_DATA)
    public ResponseEntity putPackage(@RequestHeader(name = "X-NuGet-ApiKey", required = false) String apiKey,
                                     @ApiParam(value = "The storageId", required = true) @PathVariable(name = "storageId") String storageId,
                                     @ApiParam(value = "The repositoryId", required = true) @PathVariable(name = "repositoryId") String repositoryId,
                                     HttpServletRequest request)
    {
        logger.info(String.format("Nuget push request: storageId-[%s]; repositoryId-[%s]", storageId, repositoryId));

        String userName = getUserName();
        if (!verify(userName, apiKey))
        {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String contentType = request.getHeader("content-type");

        URI resourceUri;
        try
        {
            ServletInputStream is = request.getInputStream();
            FileInputStream packagePartInputStream = extractPackageMultipartStream(extractBoundary(contentType), is);

            if (packagePartInputStream == null)
            {
                logger.error(String.format("Failed to extract Nuget package from request: [%s]:[%s]",
                                           storageId,
                                           repositoryId));

                return ResponseEntity.badRequest().build();
            }

            resourceUri = storePackage(storageId, repositoryId, packagePartInputStream);
        }
        catch (Exception e)
        {
            logger.error(String.format("Failed to process Nuget push request: %s:%s", storageId, repositoryId), e);

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }

        if (resourceUri == null)
        {
            // Return 501 status in case of empty package recieved.
            // For some reason nuget.exe sends empty package first.
            return ResponseEntity.status(HttpURLConnection.HTTP_NOT_IMPLEMENTED).build();
        }

        return ResponseEntity.created(resourceUri).build();
    }

    @ApiOperation(value = "Used to download a package")
    @ApiResponses(value = { @ApiResponse(code = HttpURLConnection.HTTP_OK, message = "The package was downloaded successfully."),
                            @ApiResponse(code = HttpURLConnection.HTTP_INTERNAL_ERROR, message = "An error occurred.") })
    @PreAuthorize("hasAuthority('ARTIFACTS_RESOLVE')")
    @RequestMapping(path = "{storageId}/{repositoryId}/download/{packageId}/{packageVersion}", method = RequestMethod.GET, produces = MediaType.APPLICATION_OCTET_STREAM)
    public void getPackage(@ApiParam(value = "The storageId", required = true) @PathVariable(name = "storageId") String storageId,
                           @ApiParam(value = "The repositoryId", required = true) @PathVariable(name = "repositoryId") String repositoryId,
                           @ApiParam(value = "The packageId", required = true) @PathVariable(name = "packageId") String packageId,
                           @ApiParam(value = "The packageVersion", required = true) @PathVariable(name = "packageVersion") String packageVersion,
                           HttpServletResponse response)
            throws IOException
    {
        Storage storage = configurationManager.getConfiguration().getStorage(storageId);
        Repository repository = storage.getRepository(repositoryId);

        if (!repository.isInService())
        {
            logger.error("Repository is not in service...");

            response.sendError(HttpStatus.SERVICE_UNAVAILABLE.value(),
                               "The " + storageId + ":" + repositoryId + " repository is currently out of service.");
        }

        String path = String.format("%s/%s/%s.%s.nupkg", packageId, packageVersion, packageId, packageVersion);

        try
        {
            ArtifactInputStream is = (ArtifactInputStream) getArtifactManagementService().resolve(storageId,
                                                                                                  repositoryId,
                                                                                                  path);
            if (is == null)
            {
                logger.debug("Unable to find artifact by path " + path);

                response.setStatus(NOT_FOUND.value());
            }

            try (TempNupkgFile nupkgFile = new TempNupkgFile(is))
            {
                response.setHeader("Content-Disposition", String.format("attachment; filename=\"%s\"", nupkgFile.getFileName()));
                ArtifactControllerHelper.setHeadersForChecksums(is, response);

                // unfortunately 'new TempNupkgFile(is)' closes 'is' stream :(
                try (InputStream isCopy = getArtifactManagementService().resolve(storageId, repositoryId, path))
                {
                    copyToResponse(isCopy, response);
                }
            }

        }
        catch (Exception e)
        {
            logger.error(String.format("Failed to process Nuget get request: %s:%s:%s:%s",
                                       storageId,
                                       repositoryId,
                                       packageId,
                                       packageVersion),
                         e);

            response.setStatus(INTERNAL_SERVER_ERROR.value());
        }
    }

    private String extractBoundary(String contentType)
    {
        String boundaryString = "";
        Pattern pattern = Pattern.compile("multipart/form-data;(\\s*)boundary=([^;]+)(.*)");
        Matcher matcher = pattern.matcher(contentType);
        if (matcher.matches())
        {
            boundaryString = matcher.group(2);
        }
        return boundaryString;
    }

    private FileInputStream extractPackageMultipartStream(String boundaryString,
                                                          ServletInputStream is)
            throws IOException
    {
        if (StringUtils.isEmpty(boundaryString))
        {
            return null;
        }

        File packagePartFile = File.createTempFile("nupkg", "part");
        try (FileOutputStream packagePartOutputStream = new FileOutputStream(packagePartFile))
        {

            writePackagePart(boundaryString, is, packagePartOutputStream);
        }

        return new FileInputStream(packagePartFile);
    }

    private void writePackagePart(String boundaryString,
                                  InputStream is,
                                  FileOutputStream packagePartOutputStream)
        throws IOException
    {
        // According specification the final Boundary of MultipartStream should be prefixed with `0x0D0x0A0x2D0x2D`
        // characters, but seems that Nuget command line tool has broken Multipart Boundary format.
        // We need to fix missing starting byte of ending Mulipart boundary (0x0D), which is incorrectly generated by
        // NuGet `push` implementation.
        byte[] boundary = boundaryString.getBytes();
        byte[] boundaryPrefixToFix = {0x00, 0x0A, 0x2D, 0x2D};
        byte[] boundaryPrefixTarget = {0x00, 0x0D, 0x0A, 0x2D, 0x2D};
        
        byte[] replacementPattern = new byte[boundary.length + 4];
        byte[] replacementTarget = new byte[boundary.length + 5];

        System.arraycopy(boundaryPrefixToFix, 0, replacementPattern, 0, 4);
        System.arraycopy(boundaryPrefixTarget, 0, replacementTarget, 0, 5);
        
        System.arraycopy(boundary, 0, replacementPattern, 4, boundary.length);
        System.arraycopy(boundary, 0, replacementTarget, 4, boundary.length);
        
        Path streamContentPath = Files.createTempFile("boundaryString", "nupkg");
        ReplacingInputStream replacingIs = new ReplacingInputStream(is, boundaryPrefixToFix, boundaryPrefixTarget);
        long len = Files.copy(replacingIs, streamContentPath,
                   StandardCopyOption.REPLACE_EXISTING);
        System.out.println(len);
        
        try (InputStream streamContentIs = Files.newInputStream(streamContentPath))
        {
            MultipartStream multipartStream = new MultipartStream(streamContentIs, boundary);
            multipartStream.skipPreamble();
            String header = multipartStream.readHeaders();

            // Package Multipart Header should be like follows:
            // Content-Disposition: form-data; name="package";
            // filename="package"
            // Content-Type: application/octet-stream
            if (!header.contains("package"))
            {
                logger.error("Invalid package multipart format");
                return;
            }

            int contentLength = multipartStream.readBodyData(packagePartOutputStream);
            logger.info(String.format("NuGet package content length [%s]", contentLength));
        }
    }

    private URI storePackage(String storageId,
                             String repositoryId,
                             InputStream is)
        throws Exception
    {
        try (TempNupkgFile nupkgFile = new TempNupkgFile(is))
        {
            if (nupkgFile.getNuspecFile() == null)
            {
                return null;
            }

            String path = String.format("%s/%s/%s.%s.nupkg",
                                        nupkgFile.getId(),
                                        nupkgFile.getVersion(),
                                        nupkgFile.getId(),
                                        nupkgFile.getVersion());

            getArtifactManagementService().store(storageId, repositoryId, path, nupkgFile.getStream());

            File nuspecFile = File.createTempFile(nupkgFile.getId(), "nuspec");
            try (FileOutputStream fileOutputStream = new FileOutputStream(nuspecFile))
            {
                nupkgFile.getNuspecFile().saveTo(fileOutputStream);
            }
            path = String.format("%s/%s/%s.nuspec", nupkgFile.getId(), nupkgFile.getVersion(), nupkgFile.getId());

            getArtifactManagementService().store(storageId, repositoryId, path, new FileInputStream(nuspecFile));

            File hashFile = File.createTempFile(String.format("%s.%s", nupkgFile.getId(), nupkgFile.getVersion()),
                                                "nupkg.sha512");
            nupkgFile.getHash().saveTo(hashFile);

            path = String.format("%s/%s/%s.%s.nupkg.sha512",
                                 nupkgFile.getId(),
                                 nupkgFile.getVersion(),
                                 nupkgFile.getId(),
                                 nupkgFile.getVersion());

            getArtifactManagementService().store(storageId, repositoryId, path, new FileInputStream(hashFile));
        }

        return new URI("");
    }

    private boolean verify(String userName,
                           String apiKey)
    {
        if (StringUtils.isEmpty(userName) || StringUtils.isEmpty(apiKey))
        {
            return false;
        }

        try
        {
            userService.verifySecurityToken(userName, apiKey);
        }
        catch (SecurityTokenException e)
        {
            logger.info(String.format("Invalid security token: user-[%s]", userName), e);

            return false;
        }

        return true;
    }

    private String getUserName()
    {
        SecurityContext securityContext = SecurityContextHolder.getContext();
        Authentication authentication = securityContext == null ? null : securityContext.getAuthentication();

        return authentication == null ? null : authentication.getName();
    }

    public ArtifactManagementService getArtifactManagementService()
    {
        return nugetArtifactManagementService;
    }

    private String normaliseSearchTerm(String sourceValue)
    {
        if (sourceValue == null)
        {
            return null;
        }

        return sourceValue.replaceAll("['\"]", "").toLowerCase();
    }

    public static final void main(String[] args){
        Pattern pattern = Pattern.compile("(?:Packages(?:\\(\\))?|Search\\(\\))");
        System.out.println(pattern.matcher("Packages").matches());;
        System.out.println(pattern.matcher("Packages()").matches());;
        System.out.println(pattern.matcher("Search").matches());;
        System.out.println(pattern.matcher("Search()").matches());;
    }
}
