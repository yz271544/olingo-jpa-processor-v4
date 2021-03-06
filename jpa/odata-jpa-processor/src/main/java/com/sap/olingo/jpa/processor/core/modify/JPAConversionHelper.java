package com.sap.olingo.jpa.processor.core.modify;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.persistence.AttributeConverter;

import org.apache.olingo.commons.api.data.ComplexValue;
import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.commons.api.data.Parameter;
import org.apache.olingo.commons.api.data.Property;
import org.apache.olingo.commons.api.data.ValueType;
import org.apache.olingo.commons.api.edm.EdmEntitySet;
import org.apache.olingo.commons.api.format.ContentType;
import org.apache.olingo.commons.api.http.HttpStatusCode;
import org.apache.olingo.server.api.OData;
import org.apache.olingo.server.api.ODataRequest;
import org.apache.olingo.server.api.deserializer.DeserializerException;
import org.apache.olingo.server.api.deserializer.DeserializerResult;
import org.apache.olingo.server.api.deserializer.ODataDeserializer;
import org.apache.olingo.server.api.serializer.SerializerException;
import org.apache.olingo.server.api.uri.UriParameter;

import com.sap.olingo.jpa.metadata.core.edm.mapper.api.JPAAttribute;
import com.sap.olingo.jpa.metadata.core.edm.mapper.api.JPAEntityType;
import com.sap.olingo.jpa.metadata.core.edm.mapper.api.JPAEnumerationAttribute;
import com.sap.olingo.jpa.metadata.core.edm.mapper.api.JPAPath;
import com.sap.olingo.jpa.metadata.core.edm.mapper.api.JPAServiceDocument;
import com.sap.olingo.jpa.metadata.core.edm.mapper.api.JPAStructuredType;
import com.sap.olingo.jpa.metadata.core.edm.mapper.exception.ODataJPAModelException;
import com.sap.olingo.jpa.processor.core.exception.ODataJPAFilterException;
import com.sap.olingo.jpa.processor.core.exception.ODataJPAProcessException;
import com.sap.olingo.jpa.processor.core.exception.ODataJPAProcessorException;
import com.sap.olingo.jpa.processor.core.exception.ODataJPAProcessorException.MessageKeys;
import com.sap.olingo.jpa.processor.core.processor.deserializer.JsonDeserializer;
import com.sap.olingo.jpa.processor.core.query.ExpressionUtil;

/**
 * Helper method for modifying requests.<p>
 * Mainly created to increase testability
 * @author Oliver Grande
 *
 */

public class JPAConversionHelper {

  private final Map<Object, Map<String, Object>> getterBuffer;

  public JPAConversionHelper() {
    super();
    this.getterBuffer = new HashMap<>();
  }

  /**
   * Creates a map of attribute name and the return value of there getter method. <p>
   * It is assumed that the method name is composed from <i>get</> and the
   * name of the attribute and that the attribute name starts with a lower case
   * letter.
   * @param instance
   * @return
   * @throws ODataJPAProcessorException
   */
  public Map<String, Object> buildGetterMap(Object instance) throws ODataJPAProcessorException {

    if (instance != null) {
      Map<String, Object> getterMap = getterBuffer.get(instance);
      if (getterMap == null) {
        getterMap = new HashMap<>();
        Method[] methods = instance.getClass().getMethods();
        for (Method meth : methods) {
          String methodName = meth.getName();
          if (methodName.substring(0, 3).equals("get") && methodName.length() > 3) {
            String attributeName = methodName.substring(3, 4).toLowerCase() + methodName.substring(4);
            try {
              Object value = meth.invoke(instance);
              getterMap.put(attributeName, value);
            } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
              throw new ODataJPAProcessorException(MessageKeys.ATTRIBUTE_RETRIVAL_FAILED,
                  HttpStatusCode.INTERNAL_SERVER_ERROR, e, attributeName);
            }
          }
        }
        getterBuffer.put(instance, getterMap);
      }
      return getterMap;
    } else
      throw new ODataJPAProcessorException(MessageKeys.PARAMETER_NULL, HttpStatusCode.INTERNAL_SERVER_ERROR);
  }

  /**
   * Converts the payload of a request into the corresponding odata entity
   * @param odata
   * @param request
   * @param requestFormat
   * @param edmEntitySet
   * @return
   * @throws DeserializerException
   */
  public Entity convertInputStream(final OData odata, final ODataRequest request, final ContentType requestFormat,
      EdmEntitySet edmEntitySet) throws ODataJPAProcessorException {

    InputStream requestInputStream = request.getBody();
    DeserializerResult result;
    try {
      ODataDeserializer deserializer;
      if (requestFormat.isCompatible(ContentType.APPLICATION_JSON))
        deserializer = new JsonDeserializer(requestFormat);
      else
        deserializer = odata.createDeserializer(requestFormat);

      result = deserializer.entity(requestInputStream, edmEntitySet.getEntityType());
    } catch (DeserializerException e) {
      throw new ODataJPAProcessorException(e, HttpStatusCode.BAD_REQUEST);
    }
    return result.getEntity();
  }

  /**
   * 
   * @param odata
   * @param request
   * @param edmEntitySet
   * @param et
   * @param newPOJO
   * @return
   * @throws SerializerException
   * @throws ODataJPAProcessorException
   */

  @SuppressWarnings("unchecked")
  public String convertKeyToLocal(final OData odata, final ODataRequest request, EdmEntitySet edmEntitySet,
      JPAEntityType et, Object newPOJO) throws SerializerException, ODataJPAProcessorException {

    if (newPOJO instanceof Map<?, ?>)
      return convertKeyToLocalMap(odata, request, edmEntitySet, et, (Map<String, Object>) newPOJO);
    else
      return convertKeyToLocalEntity(odata, request, edmEntitySet, et, newPOJO);
  }

  /**
   * Creates nested map of attributes and there (new) values. Primitive values are instances of e.g. Integer. Embedded
   * Types are returned as maps.
   * @param <T>
   * @param <S>
   * 
   * @param odata
   * @param st
   * @param odataProperties
   * @return
   * @throws ODataJPAProcessException
   */
  public Map<String, Object> convertProperties(final OData odata, final JPAStructuredType st,
      final List<Property> odataProperties) throws ODataJPAProcessException {

    final Map<String, Object> jpaAttributes = new HashMap<>();
    String internalName = null;
    Object jpaAttribute = null;
    for (Property odataProperty : odataProperties) {
      switch (odataProperty.getValueType()) {
      case COMPLEX:
        try {
          JPAPath path = st.getPath(odataProperty.getName());
          internalName = path.getPath().get(0).getInternalName();
          JPAStructuredType a = st.getAttribute(internalName).getStructuredType();
          jpaAttribute = convertProperties(odata, a, ((ComplexValue) odataProperty.getValue()).getValue());
        } catch (ODataJPAModelException e) {
          throw new ODataJPAProcessorException(e, HttpStatusCode.INTERNAL_SERVER_ERROR);
        }
        break;
      case PRIMITIVE:
      case ENUM:
        try {
          final JPAAttribute attribute = st.getPath(odataProperty.getName()).getLeaf();
          internalName = attribute.getInternalName();
          jpaAttribute = processAttributeConverter(odataProperty.getValue(), attribute);
        } catch (ODataJPAModelException e) {
          throw new ODataJPAProcessorException(e, HttpStatusCode.INTERNAL_SERVER_ERROR);
        }
        break;
      default:
        throw new ODataJPAProcessorException(MessageKeys.NOT_SUPPORTED_PROP_TYPE, HttpStatusCode.NOT_IMPLEMENTED,
            odataProperty.getValueType().name());
      }
      jpaAttributes.put(internalName, jpaAttribute);
    }
    return jpaAttributes;
  }

  public static Object convertParameter(final Parameter param, final JPAServiceDocument sd)
      throws ODataJPAModelException {
    switch (param.getValueType()) {
    case ENUM:
      final JPAEnumerationAttribute enumType = sd.getEnumType(param.getType());
      return enumType.enumOf((Number) param.getValue());

    default:
      return param.getValue();
    }
  }

  /**
   * 
   * @param keyPredicates
   * @return
   * @throws ODataJPAFilterException
   * @throws ODataJPAProcessorException
   */
  public Map<String, Object> convertUriKeys(final OData odata, final JPAStructuredType st,
      final List<UriParameter> keyPredicates) throws ODataJPAFilterException, ODataJPAProcessorException {

    Map<String, Object> result = new HashMap<>(keyPredicates.size());
    String internalName;
    for (UriParameter key : keyPredicates) {
      try {
        final JPAAttribute attribute = st.getPath(key.getName()).getLeaf();
        internalName = attribute.getInternalName();
        Object jpaAttribute = ExpressionUtil.convertValueOnAttribute(odata, attribute, key.getText(), true);
        result.put(internalName, jpaAttribute);
      } catch (ODataJPAModelException e) {
        throw new ODataJPAProcessorException(e, HttpStatusCode.INTERNAL_SERVER_ERROR);
      }
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  public <S, T> Object processAttributeConverter(Object value, final JPAAttribute attribute) {
    Object jpaAttribute;
    if (attribute.getConverter() != null) {
      AttributeConverter<T, S> converter = attribute.getConverter();
      jpaAttribute = converter.convertToEntityAttribute((S) value);
    } else if (attribute.isEnum())
      jpaAttribute = findEnumConstantsByOrdinal(attribute.getType().getEnumConstants(), value);
    else
      jpaAttribute = value;
    return jpaAttribute;
  }

  private void collectKeyProperties(Map<String, Object> newPOJO, final List<JPAPath> keyPath,
      final List<Property> properties)
      throws ODataJPAProcessorException, ODataJPAModelException {

    if (keyPath.size() > 1) {

      for (JPAPath key : keyPath) {
        Object keyElement = newPOJO.get(key.getLeaf().getInternalName());
        final Property property = new Property(null, key.getLeaf().getExternalName());
        property.setValue(ValueType.PRIMITIVE, keyElement);
        properties.add(property);
      }
    } else {
      JPAPath key = keyPath.get(0);
      if (key.getLeaf().isComplex()) {
        // EmbeddedId
        @SuppressWarnings("unchecked")
        Map<String, Object> embeddedId = (Map<String, Object>) newPOJO.get(key.getLeaf().getInternalName());
        collectKeyProperties(embeddedId, key.getLeaf().getStructuredType().getPathList(), properties);
      } else {
        final Property property = new Property(null, key.getLeaf().getExternalName());
        property.setValue(ValueType.PRIMITIVE, newPOJO.get(key.getLeaf().getInternalName()));
        properties.add(property);
      }
    }
  }

  private void collectKeyProperties(Object newPOJO, final List<JPAPath> keyPath, final List<Property> properties)
      throws ODataJPAProcessorException, ODataJPAModelException {

    final Map<String, Object> getter = buildGetterMap(newPOJO);
    if (keyPath.size() > 1) {

      for (JPAPath key : keyPath) {
        final Property property = new Property(null, key.getLeaf().getExternalName());
        property.setValue(ValueType.PRIMITIVE, getter.get(key.getLeaf().getInternalName()));
        properties.add(property);
      }
    } else {
      JPAPath key = keyPath.get(0);
      if (key.getLeaf().isComplex()) {
        // EmbeddedId
        Object embeddedId = getter.get(key.getLeaf().getInternalName());
        collectKeyProperties(embeddedId, key.getLeaf().getStructuredType().getPathList(), properties);
      } else {
        final Property property = new Property(null, key.getLeaf().getExternalName());
        property.setValue(ValueType.PRIMITIVE, getter.get(key.getLeaf().getInternalName()));
        properties.add(property);
      }
    }
  }

  private String convertKeyToLocalEntity(final OData odata, final ODataRequest request, EdmEntitySet edmEntitySet,
      JPAEntityType et, Object newPOJO) throws SerializerException, ODataJPAProcessorException {

    final Entity createdEntity = new Entity();

    try {
      final List<JPAPath> keyPath = et.getKeyPath();
      final List<Property> properties = createdEntity.getProperties();

      collectKeyProperties(newPOJO, keyPath, properties);
    } catch (ODataJPAModelException e) {
      throw new ODataJPAProcessorException(e, HttpStatusCode.BAD_REQUEST);
    }

    return request.getRawBaseUri() + '/'
        + odata.createUriHelper().buildCanonicalURL(edmEntitySet, createdEntity);
  }

  private String convertKeyToLocalMap(final OData odata, final ODataRequest request, EdmEntitySet edmEntitySet,
      JPAEntityType et, Map<String, Object> newPOJO) throws SerializerException, ODataJPAProcessorException {

    final Entity createdEntity = new Entity();

    try {
      final List<Property> properties = createdEntity.getProperties();
      final List<JPAPath> keyPath = et.getKeyPath();
      collectKeyProperties(newPOJO, keyPath, properties);
    } catch (ODataJPAModelException e) {
      throw new ODataJPAProcessorException(e, HttpStatusCode.BAD_REQUEST);
    }

    return request.getRawBaseUri() + '/'
        + odata.createUriHelper().buildCanonicalURL(edmEntitySet, createdEntity);
  }

  private <T> Object findEnumConstantsByOrdinal(T[] enumConstants, Object value) {
    for (int i = 0; i < enumConstants.length; i++) {
      if (((Enum<?>) enumConstants[i]).ordinal() == (Integer) value)
        return enumConstants[i];
    }
    return null;
  }

}
