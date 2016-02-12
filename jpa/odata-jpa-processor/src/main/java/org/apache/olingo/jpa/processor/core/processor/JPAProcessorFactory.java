package org.apache.olingo.jpa.processor.core.processor;

import java.util.List;
import java.util.Locale;

import javax.persistence.EntityManager;

import org.apache.olingo.commons.api.format.ContentType;
import org.apache.olingo.commons.api.http.HttpStatusCode;
import org.apache.olingo.jpa.metadata.core.edm.mapper.impl.ServicDocument;
import org.apache.olingo.jpa.processor.core.serializer.JPASerializerFactory;
import org.apache.olingo.server.api.OData;
import org.apache.olingo.server.api.ODataApplicationException;
import org.apache.olingo.server.api.ODataLibraryException;
import org.apache.olingo.server.api.ServiceMetadata;
import org.apache.olingo.server.api.uri.UriInfo;
import org.apache.olingo.server.api.uri.UriResource;
import org.apache.olingo.server.api.uri.UriResourceKind;

public class JPAProcessorFactory {
  private final ServicDocument sd;
  private final JPASerializerFactory serializerFactory;

  public JPAProcessorFactory(OData odata, ServiceMetadata serviceMetadata, ServicDocument sd) {
    super();
    this.sd = sd;
    this.serializerFactory = new JPASerializerFactory(odata, serviceMetadata);
  }

  public JPARequestProcessor createProcessor(EntityManager em, UriInfo uriInfo, ContentType responseFormat)
      throws ODataApplicationException,
      ODataLibraryException {
    List<UriResource> resourceParts = uriInfo.getUriResourceParts();
    UriResource lastItem = resourceParts.get(resourceParts.size() - 1);

    switch (lastItem.getKind()) {
    case count:
      return new JPACountRequestProcessor(sd, em, uriInfo, serializerFactory.createSerializer(responseFormat,
          uriInfo));
    case function:
      checkFunctionPathSupported(resourceParts);
      return new JPAFunctionRequestProcessor(sd, em, uriInfo, serializerFactory.createSerializer(responseFormat,
          uriInfo));
    case complexProperty:
    case primitiveProperty:
    case navigationProperty:
    case entitySet:
      checkNavigationPathSupported(resourceParts);
      return new JPANavigationRequestProcessor(sd, em, uriInfo, serializerFactory.createSerializer(responseFormat,
          uriInfo));
    default:
      throw new ODataApplicationException("Not implemented",
          HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(), Locale.ENGLISH);
    }
  }

  private void checkFunctionPathSupported(List<UriResource> resourceParts) throws ODataApplicationException {
    if (resourceParts.size() > 1)
      throw new ODataApplicationException("Functions within a navigation path not supported",
          HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(), Locale.ENGLISH);
  }

  private void checkNavigationPathSupported(List<UriResource> resourceParts) throws ODataApplicationException {
    for (UriResource resourceItem : resourceParts) {
      if (resourceItem.getKind() != UriResourceKind.complexProperty
          && resourceItem.getKind() != UriResourceKind.primitiveProperty
          && resourceItem.getKind() != UriResourceKind.navigationProperty
          && resourceItem.getKind() != UriResourceKind.entitySet)
        throw new ODataApplicationException("Not implemented",
            HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(), Locale.ENGLISH);
    }

  }
}
