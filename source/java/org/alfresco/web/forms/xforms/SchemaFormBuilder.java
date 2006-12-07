/*
 * Copyright (C) 2005 Alfresco, Inc.
 *
 * Licensed under the Mozilla Public License version 1.1 
 * with a permitted attribution clause. You may obtain a
 * copy of the License at
 *
 *   http://www.alfresco.org/legal/license.txt
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the
 * License.
 */
package org.alfresco.web.forms.xforms;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.util.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.*;
import org.alfresco.web.forms.FormsService;
import org.apache.commons.jxpath.JXPathContext;
import org.apache.commons.jxpath.Pointer;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.xerces.xs.*;
import org.chiba.xml.util.DOMUtil;
import org.chiba.xml.xforms.NamespaceCtx;
import org.w3c.dom.*;
import org.w3c.dom.bootstrap.DOMImplementationRegistry;
import org.w3c.dom.ls.*;
import org.xml.sax.InputSource;

/**
 * An abstract implementation of the SchemaFormBuilder interface allowing
 * an XForm to be automatically generated for an XML Schema definition.
 * This abstract class implements the buildForm and buildFormAsString methods
 * and associated helper but relies on concrete subclasses to implement other
 * required interface methods (createXXX, startXXX, and endXXX methods).
 *
 * @author $Author: unl $
 */
public class SchemaFormBuilder 
{

   ////////////////////////////////////////////////////////////////////////////

   private final Comparator typeExtensionSorter = new Comparator() 
   {
      public int compare(Object obj1, Object obj2) 
      {
         if (obj1 == null && obj2 != null)
            return -1;
         else if (obj1 != null && obj2 == null)
            return 1;
         else if (obj1 == obj2 || (obj1 == null && obj2 == null))
            return 0;
         else 
         {
            try
            {
               final XSTypeDefinition type1 = (XSTypeDefinition) obj1;
               final XSTypeDefinition type2 = (XSTypeDefinition) obj2;
               return (type1.derivedFromType(type2, XSConstants.DERIVATION_EXTENSION)
                       ? 1
                       : (type2.derivedFromType(type1, XSConstants.DERIVATION_EXTENSION)
                          ? -1
                          : 0));
            }
            catch (ClassCastException ex) 
            {
               String s = "ClassCastException in typeExtensionSorter: one of the types is not a type !";
               s = s + "\n obj1 class = " + obj1.getClass().getName() + ", toString=" + obj1.toString();
               s = s + "\n obj2 class = " + obj2.getClass().getName() + ", toString=" + obj2.toString();
               SchemaFormBuilder.LOGGER.error(s, ex);
               return 0;
            }
         }
      }
   };

   ////////////////////////////////////////////////////////////////////////////

   public static class Occurs
   {
      public final static int UNBOUNDED = -1;
	
      public final int minimum;
      public final int maximum;

      public Occurs(final XSParticle particle)
      {
         if (particle == null)
         {
            this.minimum = 1;
            this.maximum = 1;
         }
         else
         {
            this.minimum = particle.getMinOccurs();
            this.maximum = (particle.getMaxOccursUnbounded()
                            ? Occurs.UNBOUNDED
                            : particle.getMaxOccurs());
         }
      }

      public Occurs(final int minimum)
      {
         this(minimum, UNBOUNDED);
      }

      public Occurs(final int minimum, final int maximum)
      {
         this.minimum = minimum;
         this.maximum = maximum;
      }

      public boolean isRepeated()
      {
         return this.isUnbounded() || this.maximum > 1;
      }
      
      public boolean isUnbounded()
      {
         return this.maximum == UNBOUNDED;
      }

      public String toString()
      {
         return "minimum=" + minimum + ", maximum=" + maximum;
      }
   }

   ////////////////////////////////////////////////////////////////////////////

   public final static Log LOGGER = 
      LogFactory.getLog(SchemaFormBuilder.class);

   /**
    * XMLSchema Namespace declaration
    */
   public static final String XMLSCHEMA_NS =
      "http://www.w3.org/2001/XMLSchema";

   /**
    * XMLSchema prefix
    */
   public static final String XMLSCHEMA_NS_PREFIX = "xs:";

   /**
    * XMLSchema Instance Namespace declaration
    */
   public static final String XMLSCHEMA_INSTANCE_NS = 
      "http://www.w3.org/2001/XMLSchema-instance";

   /**
    * XMLSchema instance prefix
    */
   public static final String XMLSCHEMA_INSTANCE_NS_PREFIX = "xsi:";

   /**
    * XMLNS Namespace declaration.
    */
   public static final String XMLNS_NAMESPACE_URI =
      "http://www.w3.org/2000/xmlns/";

   /**
    * XML Namespace declaration
    */
   public static final String XML_NAMESPACE_URI =
      "http://www.w3.org/XML/1998/namespace";

   /**
    * XForms namespace declaration.
    */
   public static final String XFORMS_NS = 
      "http://www.w3.org/2002/xforms";

   /**
    * XForms prefix
    */
   public static final String XFORMS_NS_PREFIX = "xforms:";

   /**
    * Alfresco namespace declaration.
    */
   public static final String ALFRESCO_NS =
      "http://www.alfresco.org/alfresco";

   /**
    * Alfresco prefix
    */
   public static final String ALFRESCO_NS_PREFIX = "alfresco:";

   /**
    * Chiba namespace declaration.
    */
   public static final String CHIBA_NS =
      "http://chiba.sourceforge.net/xforms";

   /**
    * Chiba prefix
    */
   public static final String CHIBA_NS_PREFIX = "chiba:";

   /**
    * XLink namespace declaration.
    */
   public static final String XLINK_NS = "http://www.w3.org/1999/xlink";

   /**
    * Xlink prefix
    */
   public static final String XLINK_NS_PREFIX = "xlink:";

   /**
    * XML Events namsepace declaration.
    */
   public static final String XMLEVENTS_NS = "http://www.w3.org/2001/xml-events";

   /**
    * XML Events prefix
    */
   public static final String XMLEVENTS_NS_PREFIX = "ev:";

   /**
    * Prossible values of the "@method" on the "submission" element
    */
   public static final String SUBMIT_METHOD_POST = "post";

   /**
    * __UNDOCUMENTED__
    */
   public static final String SUBMIT_METHOD_PUT = "put";

   /**
    * __UNDOCUMENTED__
    */
   public static final String SUBMIT_METHOD_GET = "get";

   /**
    * __UNDOCUMENTED__
    */
   public static final String SUBMIT_METHOD_FORM_DATA_POST = "form-data-post";

   /**
    * __UNDOCUMENTED__
    */
   public static final String SUBMIT_METHOD_URLENCODED_POST =
      "urlencoded-post";

   private static final String PROPERTY_PREFIX =
      "http://www.chiba.org/properties/schemaFormBuilder/";
   /**
    * Property to control the cascading style sheet used for the XForm - corresponds to envelope@chiba:css-style.
    */
   public static final String CSS_STYLE_PROP =
      PROPERTY_PREFIX + "envelope@css-style";
   private static final String DEFAULT_CSS_STYLE_PROP = "style.css";

   /**
    * Property to control the selection of UI control for a selectOne control.
    * If a selectOne control has >= the number of values specified in this property,
    * it is considered a <b>long</b> list, and the UI control specified by
    * SELECTONE_UI_CONTROL_LONG_PROP is used. Otherwise, the value of SELECTONE_UI_CONTROL_SHORT_PROP
    * is used.
    */
   public static final String SELECTONE_LONG_LIST_SIZE_PROP =
      PROPERTY_PREFIX + "select1@longListSize";

   /**
    * Property to specify the selectMany UI control to be used when there are releatively few items
    * to choose from.
    */
   public static final String SELECTONE_UI_CONTROL_SHORT_PROP =
      PROPERTY_PREFIX + "select1@appearance/short";

   /**
    * Property to specify the selectMany UI control to be used when there are large numbers of items
    * to choose from.
    */
   public static final String SELECTONE_UI_CONTROL_LONG_PROP =
      PROPERTY_PREFIX + "select1@appearance/long";
   private static final String DEFAULT_SELECTONE_UI_CONTROL_SHORT_PROP =
      "full";
   private static final String DEFAULT_SELECTONE_UI_CONTROL_LONG_PROP =
      "minimal";

   /**
    * Property to control the selection of UI control for a selectMany control.
    * If a selectMany control has >= the number of values specified in this property,
    * it is considered a <b>long</b> list, and the UI control specified by
    * SELECTMANY_UI_CONTROL_LONG_PROP is used. Otherwise, the value of SELECTMANY_UI_CONTROL_SHORT_PROP
    * is used.
    */
   public static final String SELECTMANY_LONG_LIST_SIZE_PROP =
      PROPERTY_PREFIX + "select@longListSize";

   /**
    * Property to specify the selectMany UI control to be used when there are releatively few items
    * to choose from.
    */
   public static final String SELECTMANY_UI_CONTROL_SHORT_PROP =
      PROPERTY_PREFIX + "select@appearance/short";

   /**
    * Property to specify the selectMany UI control to be used when there are large numbers of items
    * to choose from.
    */
   public static final String SELECTMANY_UI_CONTROL_LONG_PROP =
      PROPERTY_PREFIX + "select@appearance/long";
   private static final String DEFAULT_SELECTMANY_UI_CONTROL_SHORT_PROP =
      "full";
   private static final String DEFAULT_SELECTMANY_UI_CONTROL_LONG_PROP =
      "compact";
   private static final String DEFAULT_LONG_LIST_MAX_SIZE = "6";

   private final String action;
   private final String submitMethod;
   private final String base;
   protected WrapperElementsBuilder wrapper = new XHTMLWrapperElementsBuilder();

   /**
    * generic counter -> replaced by an hashMap with:
    * keys: name of the elements
    * values: "Long" representing the counter for this element
    */
   private HashMap counter;
   private final Properties properties = new Properties();
   private String targetNamespace;
   private final Map namespacePrefixes = new HashMap();

   // typeTree
   // each entry is keyed by the type name
   // value is an ArrayList that contains the XSTypeDefinition's which
   // are compatible with the specific type. Compatible means that
   // can be used as a substituted type using xsi:type
   // In order for it to be compatible, it cannot be abstract, and
   // it must be derived by extension.
   // The ArrayList does not contain its own type + has the other types only once
   private final TreeMap<String, TreeSet<XSTypeDefinition>> typeTree = 
      new TreeMap<String, TreeSet<XSTypeDefinition>>();

   private final static HashMap<Short, String> DATA_TYPE_TO_NAME = 
      new HashMap<Short, String>();
   static
   {
      SchemaFormBuilder.DATA_TYPE_TO_NAME.put(XSConstants.ANYSIMPLETYPE_DT, "anyType");
      SchemaFormBuilder.DATA_TYPE_TO_NAME.put(XSConstants.ANYURI_DT, "anyURI");
      SchemaFormBuilder.DATA_TYPE_TO_NAME.put(XSConstants.BASE64BINARY_DT, "base64Binary");
      SchemaFormBuilder.DATA_TYPE_TO_NAME.put(XSConstants.BOOLEAN_DT, "boolean");
      SchemaFormBuilder.DATA_TYPE_TO_NAME.put(XSConstants.BYTE_DT, "byte");
      SchemaFormBuilder.DATA_TYPE_TO_NAME.put(XSConstants.DATETIME_DT, "dateTime");
      SchemaFormBuilder.DATA_TYPE_TO_NAME.put(XSConstants.DATE_DT, "date");
      SchemaFormBuilder.DATA_TYPE_TO_NAME.put(XSConstants.DECIMAL_DT, "decimal");
      SchemaFormBuilder.DATA_TYPE_TO_NAME.put(XSConstants.DOUBLE_DT, "double");
      SchemaFormBuilder.DATA_TYPE_TO_NAME.put(XSConstants.DURATION_DT, "duration");
      SchemaFormBuilder.DATA_TYPE_TO_NAME.put(XSConstants.ENTITY_DT, "ENTITY");
      SchemaFormBuilder.DATA_TYPE_TO_NAME.put(XSConstants.FLOAT_DT, "float");
      SchemaFormBuilder.DATA_TYPE_TO_NAME.put(XSConstants.GDAY_DT, "gDay");
      SchemaFormBuilder.DATA_TYPE_TO_NAME.put(XSConstants.GMONTHDAY_DT, "gMonthDay");
      SchemaFormBuilder.DATA_TYPE_TO_NAME.put(XSConstants.GMONTH_DT, "gMonth");
      SchemaFormBuilder.DATA_TYPE_TO_NAME.put(XSConstants.GYEARMONTH_DT, "gYearMonth");
      SchemaFormBuilder.DATA_TYPE_TO_NAME.put(XSConstants.GYEAR_DT, "gYear");
      SchemaFormBuilder.DATA_TYPE_TO_NAME.put(XSConstants.IDREF_DT, "IDREF");
      SchemaFormBuilder.DATA_TYPE_TO_NAME.put(XSConstants.ID_DT, "ID");
      SchemaFormBuilder.DATA_TYPE_TO_NAME.put(XSConstants.INTEGER_DT, "integer");
      SchemaFormBuilder.DATA_TYPE_TO_NAME.put(XSConstants.INT_DT, "int");
      SchemaFormBuilder.DATA_TYPE_TO_NAME.put(XSConstants.LANGUAGE_DT, "language");
      SchemaFormBuilder.DATA_TYPE_TO_NAME.put(XSConstants.LONG_DT, "long");
      SchemaFormBuilder.DATA_TYPE_TO_NAME.put(XSConstants.NAME_DT, "Name");
      SchemaFormBuilder.DATA_TYPE_TO_NAME.put(XSConstants.NCNAME_DT, "NCName");
      SchemaFormBuilder.DATA_TYPE_TO_NAME.put(XSConstants.NEGATIVEINTEGER_DT, "negativeInteger");
      SchemaFormBuilder.DATA_TYPE_TO_NAME.put(XSConstants.NMTOKEN_DT, "NMTOKEN");
      SchemaFormBuilder.DATA_TYPE_TO_NAME.put(XSConstants.NONNEGATIVEINTEGER_DT, "nonNegativeInteger");
      SchemaFormBuilder.DATA_TYPE_TO_NAME.put(XSConstants.NONPOSITIVEINTEGER_DT, "nonPositiveInteger");
      SchemaFormBuilder.DATA_TYPE_TO_NAME.put(XSConstants.NORMALIZEDSTRING_DT, "normalizedString");
      SchemaFormBuilder.DATA_TYPE_TO_NAME.put(XSConstants.NOTATION_DT, "NOTATION");
      SchemaFormBuilder.DATA_TYPE_TO_NAME.put(XSConstants.POSITIVEINTEGER_DT, "positiveInteger");
      SchemaFormBuilder.DATA_TYPE_TO_NAME.put(XSConstants.QNAME_DT, "QName");
      SchemaFormBuilder.DATA_TYPE_TO_NAME.put(XSConstants.SHORT_DT, "short");
      SchemaFormBuilder.DATA_TYPE_TO_NAME.put(XSConstants.STRING_DT, "string");
      SchemaFormBuilder.DATA_TYPE_TO_NAME.put(XSConstants.TIME_DT, "time");
      SchemaFormBuilder.DATA_TYPE_TO_NAME.put(XSConstants.TOKEN_DT, "TOKEN");
      SchemaFormBuilder.DATA_TYPE_TO_NAME.put(XSConstants.UNSIGNEDBYTE_DT, "unsignedByte");
      SchemaFormBuilder.DATA_TYPE_TO_NAME.put(XSConstants.UNSIGNEDINT_DT, "unsignedInt");
      SchemaFormBuilder.DATA_TYPE_TO_NAME.put(XSConstants.UNSIGNEDLONG_DT, "unsignedLong");
      SchemaFormBuilder.DATA_TYPE_TO_NAME.put(XSConstants.UNSIGNEDSHORT_DT, "unsignedShort");
   };

   /**
    * Creates a new SchemaFormBuilder object.
    *
    * @param rootElementName    __UNDOCUMENTED__
    * @param instanceSource __UNDOCUMENTED__
    * @param action         __UNDOCUMENTED__
    * @param submitMethod   __UNDOCUMENTED__
    * @param wrapper        __UNDOCUMENTED__
    */
   public SchemaFormBuilder(final String action,
                            final String submitMethod,
                            final WrapperElementsBuilder wrapper,
                            final String base) 
   {
      reset();

      this.action = action;
      this.submitMethod = submitMethod;
      this.wrapper = wrapper;
      this.base = base;
   }

   /**
    * Get the current set of properties used by implementations of SchemaFormBuilder.
    *
    * @return The list of properties.
    */
   public Properties getProperties() 
   {
      return properties;
   }

   /**
    * Sets the property to the specified value. If the property exists, its value is overwritten.
    *
    * @param key   The implementation defined property key.
    * @param value The value for the property.
    */
   public void setProperty(String key, String value) 
   {
      getProperties().setProperty(key, value);
   }

   /**
    * Gets the value for the specified property.
    *
    * @param key The implementation defined property key.
    * @return The property value if found, or null if the property cannot be located.
    */
   public String getProperty(String key) 
   {
      return getProperties().getProperty(key);
   }

   /**
    * Gets the value for the specified property, with a default if the property cannot be located.
    *
    * @param key          The implementation defined property key.
    * @param defaultValue This value will be returned if the property does not exists.
    * @return The property value if found, or defaultValue if the property cannot be located.
    */
   public String getProperty(String key, String defaultValue) 
   {
      return getProperties().getProperty(key, defaultValue);
   }

   /**
    * Generate the XForm based on a user supplied XML Schema.
    *
    * @param inputURI The document source for the XML Schema.
    * @return The Document containing the XForm.
    * @throws org.chiba.tools.schemabuilder.FormBuilderException
    *          If an error occurs building the XForm.
    */
   public Document buildForm(final Document instanceDocument,
                             final Document schemaDocument,
                             String rootElementName) 
      throws FormBuilderException 
   {
      final XSModel schema = this.loadSchema(schemaDocument);
      this.buildTypeTree(schema);
	
      //refCounter = 0;
      this.counter = new HashMap();
	
      final Document xForm = createFormTemplate(rootElementName);
	
      //find form element: last element created
      final Element formSection = (Element)
         xForm.getDocumentElement().getLastChild();
      final Element modelSection = (Element)
         xForm.getDocumentElement().getElementsByTagNameNS(SchemaFormBuilder.XFORMS_NS, "model").item(0);
	
      //add XMLSchema if we use schema types
      modelSection.setAttributeNS(SchemaFormBuilder.XFORMS_NS, "schema", "#schema-1");
      final Element importedSchemaRootElement = (Element)
         xForm.importNode(schemaDocument.getDocumentElement(), true);
      importedSchemaRootElement.setAttributeNS(SchemaFormBuilder.XFORMS_NS,
                                               "id",
                                               "schema-1");

      modelSection.appendChild(importedSchemaRootElement);
	
      //check if target namespace
      final StringList targetNamespaces = schema.getNamespaces();
      if (targetNamespaces.getLength() != 0)
      {
         // will return null if no target namespace was specified
         this.targetNamespace = targetNamespaces.item(0);
      }
      LOGGER.debug("using targetNamespace " + this.targetNamespace);
	
      //if target namespace & we use the schema types: add it to form ns declarations
//	if (this.targetNamespace != null && this.targetNamespace.length() != 0)
//	    envelopeElement.setAttributeNS(XMLNS_NAMESPACE_URI,
//					   "xmlns:schema",
//					   this.targetNamespace);


      //TODO: WARNING: in Xerces 2.6.1, parameters are switched !!! (name, namespace)
      //XSElementDeclaration rootElementDecl =schema.getElementDeclaration(this.targetNamespace, _rootElementName);
      XSElementDeclaration rootElementDecl = 
         schema.getElementDeclaration(rootElementName, this.targetNamespace);
	
      if (rootElementDecl == null) 
      {
         //Debug
         rootElementDecl = schema.getElementDeclaration(this.targetNamespace,  
                                                        rootElementName);
         if (rootElementDecl != null && LOGGER.isDebugEnabled())
            LOGGER.debug("getElementDeclaration: inversed parameters OK !!!");
	    
         throw new FormBuilderException("Invalid root element tag name ["
                                        + rootElementName
                                        + ", targetNamespace="
                                        + this.targetNamespace
                                        + "]");
      }
      rootElementName = this.getElementName(rootElementDecl, xForm);
      final Element instanceElement = 
         xForm.createElementNS(SchemaFormBuilder.XFORMS_NS, 
                               SchemaFormBuilder.XFORMS_NS_PREFIX + "instance");
      modelSection.appendChild(instanceElement);
      this.setXFormsId(instanceElement);

      final Element defaultInstanceRootElement = xForm.createElement(rootElementName);
      this.addNamespace(defaultInstanceRootElement, 
                        XMLSCHEMA_INSTANCE_NS_PREFIX, 
                        XMLSCHEMA_INSTANCE_NS);
	
      if (instanceDocument == null)
         instanceElement.appendChild(defaultInstanceRootElement);
      else
      {
         Element instanceDocumentElement = instanceDocument.getDocumentElement();
         if (!instanceDocumentElement.getNodeName().equals(rootElementName))
            throw new IllegalArgumentException("instance document root tag name invalid.  " +
                                               "expected " + rootElementName +
                                               ", got " + instanceDocumentElement.getNodeName());
         LOGGER.debug("importing rootElement from other document");
         final Element importedInstanceRootElement = (Element)
            xForm.importNode(instanceDocumentElement, true);
         //add XMLSchema instance NS
         this.addNamespace(importedInstanceRootElement, 
                           XMLSCHEMA_INSTANCE_NS_PREFIX, 
                           XMLSCHEMA_INSTANCE_NS);
         instanceElement.appendChild(importedInstanceRootElement);
      }

      Element formContentWrapper = this.wrapper.createGroupContentWrapper(formSection);
      this.addElement(xForm,
                      modelSection,
                      defaultInstanceRootElement,
                      formContentWrapper,
                      schema,
                      rootElementDecl,
                      rootElementDecl.getTypeDefinition(),
                      "/" + getElementName(rootElementDecl, xForm));

      Element submitInfoElement = 
         xForm.createElementNS(SchemaFormBuilder.XFORMS_NS,
                               SchemaFormBuilder.XFORMS_NS_PREFIX + "submission");
      modelSection.appendChild(submitInfoElement);

      //submitInfoElement.setAttributeNS(SchemaFormBuilder.XFORMS_NS,SchemaFormBuilder.XFORMS_NS_PREFIX+"id","save");
      String submissionId = this.setXFormsId(submitInfoElement);

      //action
      submitInfoElement.setAttributeNS(SchemaFormBuilder.XFORMS_NS,
                                       SchemaFormBuilder.XFORMS_NS_PREFIX + "action",
                                       this.action == null ? "" : this.base + this.action);

      //method
      submitInfoElement.setAttributeNS(SchemaFormBuilder.XFORMS_NS,
                                       SchemaFormBuilder.XFORMS_NS_PREFIX + "method",
                                       (this.submitMethod != null && this.submitMethod.length() != 0
                                        ? this.submitMethod
                                        :  SchemaFormBuilder.SUBMIT_METHOD_POST));

      final Element submitButton =
         xForm.createElementNS(SchemaFormBuilder.XFORMS_NS, SchemaFormBuilder.XFORMS_NS_PREFIX + "submit");
      final Element submitControlWrapper = this.wrapper.createControlsWrapper(submitButton);
      formContentWrapper.appendChild(submitControlWrapper);
      submitButton.setAttributeNS(SchemaFormBuilder.XFORMS_NS,
                                  SchemaFormBuilder.XFORMS_NS_PREFIX + "submission",
                                  submissionId);
      this.setXFormsId(submitButton);

      final Element submitButtonCaption = 
         xForm.createElementNS(SchemaFormBuilder.XFORMS_NS,
                               SchemaFormBuilder.XFORMS_NS_PREFIX + "label");
      submitButton.appendChild(submitButtonCaption);
      submitButtonCaption.appendChild(xForm.createTextNode("Submit"));
      this.setXFormsId(submitButtonCaption);
      this.createTriggersForRepeats(xForm);

      final Comment comment = 
         xForm.createComment("This XForm was automatically generated by " + this.getClass().getName() + 
                             " on " + (new Date()) + " from the '" + rootElementName + 
                             "' element of the '" + this.targetNamespace + "' XML Schema.");
      xForm.getDocumentElement().insertBefore(comment, 
                                              xForm.getDocumentElement().getFirstChild());
      return xForm;
   }

   /**
    * Reset the SchemaFormBuilder to default values.
    */
   public void reset() 
   {
      //refCounter = 0;
      counter = new HashMap();
      setProperty(CSS_STYLE_PROP, DEFAULT_CSS_STYLE_PROP);
      setProperty(SELECTMANY_LONG_LIST_SIZE_PROP, DEFAULT_LONG_LIST_MAX_SIZE);
      setProperty(SELECTMANY_UI_CONTROL_SHORT_PROP,
                  DEFAULT_SELECTMANY_UI_CONTROL_SHORT_PROP);
      setProperty(SELECTMANY_UI_CONTROL_LONG_PROP,
                  DEFAULT_SELECTMANY_UI_CONTROL_LONG_PROP);
      setProperty(SELECTONE_LONG_LIST_SIZE_PROP, DEFAULT_LONG_LIST_MAX_SIZE);
      setProperty(SELECTONE_UI_CONTROL_SHORT_PROP,
                  DEFAULT_SELECTONE_UI_CONTROL_SHORT_PROP);
      setProperty(SELECTONE_UI_CONTROL_LONG_PROP,
                  DEFAULT_SELECTONE_UI_CONTROL_LONG_PROP);
   }

   /**
    * Returns the most-specific built-in base type for the provided type.
    */
   protected short getBuiltInType(final XSTypeDefinition type) 
   {
      // type.getName() may be 'null' for anonymous types, so compare against
      // static string (see bug #1172541 on sf.net)
      if (SchemaFormBuilder.DATA_TYPE_TO_NAME.get(XSConstants.ANYSIMPLETYPE_DT).equals(type.getName())) 
      {
         return XSConstants.ANYSIMPLETYPE_DT;
      }
      final XSSimpleTypeDefinition simpleType = (XSSimpleTypeDefinition) type;

         //get built-in type
         //only working method found: getBuiltInKind, but it returns a short !
         //XSTypeDefinition builtIn = simpleType.getPrimitiveType();
         /*XSTypeDefinition builtIn = type.getBaseType();
           if (builtIn == null) {
           // always null for a ListType
           if (simpleType.getItemType() != null) //if not null it's a list
           return getBuiltInType(simpleType.getItemType());
           else
           return simpleType;
           }
           else if(LOGGER.isDebugEnabled())
           LOGGER.debug(" -> builtinType="+builtIn.getName());
           return builtIn;*/

      final short result = simpleType.getBuiltInKind();
      return (result == XSConstants.LIST_DT 
              ? this.getBuiltInType(simpleType.getItemType()) 
              : result);
   }

   protected String setXFormsId(final Element el)
   {
      return this.setXFormsId(el, null);
   }

   protected String setXFormsId(final Element el, String id) 
   {
      if (el.hasAttributeNS(SchemaFormBuilder.XFORMS_NS, "id"))
         el.removeAttributeNS(SchemaFormBuilder.XFORMS_NS, "id");
      if (id == null)
      {
         long count = 0;
         final String name = el.getLocalName();
         final Long l = (Long) counter.get(name);
	    
         if (l != null)
            count = l.longValue();
         //increment the counter
         counter.put(name, new Long(count + 1));
	    
         id = name + "_" + count;
      }
      el.setAttributeNS(SchemaFormBuilder.XFORMS_NS,
                        SchemaFormBuilder.XFORMS_NS_PREFIX + "id",
                        id);
      return id;
   }

   /**
    * method to set an Id to this element and to all XForms descendants of this element
    */
   private void resetXFormIds(Element newControl) 
   {
      if (newControl.getNamespaceURI() != null && 
          newControl.getNamespaceURI().equals(SchemaFormBuilder.XFORMS_NS))
         this.setXFormsId(newControl);

      //recursive call
      final NodeList children = newControl.getChildNodes();
      for (int i = 0; i < children.getLength(); i++) {
         final Node child = children.item(i);
         if (child.getNodeType() == Node.ELEMENT_NODE)
            this.resetXFormIds((Element) child);
      }
   }

   /**
    * __UNDOCUMENTED__
    *
    * @param xForm          __UNDOCUMENTED__
    * @param choicesElement __UNDOCUMENTED__
    * @param choiceValues   __UNDOCUMENTED__
    */
   protected void addChoicesForSelectControl(final Document xForm,
                                             final Element choicesElement,
                                             final Map<String, XSAnnotation> choiceValues) {
      // sort the enums values and then add them as choices
      //
      // TODO: Should really put the default value (if any) at the top of the list.
      //
      //        List sortedList = choiceValues.subList(0, choiceValues.size());
      //        Collections.sort(sortedList);

      //        Iterator iterator = sortedList.iterator();

      for (Map.Entry<String, XSAnnotation> choice : choiceValues.entrySet()) 
      {
         final Element item = this.createXFormsItem(xForm,
                                                    this.createCaption(choice.getKey(), choice.getValue()),
                                                    this.createCaption(choice.getKey()));
         choicesElement.appendChild(item);
      }
   }

   //protected void addChoicesForSelectSwitchControl(Document xForm, Element choicesElement, Vector choiceValues, String bindIdPrefix) {
   protected Map<String, Element> addChoicesForSelectSwitchControl(final Document xForm,
                                                                   final Element choicesElement,
                                                                   final List<XSTypeDefinition> choiceValues) 
   {
      if (LOGGER.isDebugEnabled()) 
      {
         LOGGER.debug("addChoicesForSelectSwitchControl, values=");
         for (XSTypeDefinition type : choiceValues) 
         {
            LOGGER.debug("  - " + type.getName());
         }
      }


      // sort the enums values and then add them as choices
      //
      // TODO: Should really put the default value (if any) at the top of the list.
      //
      /*List sortedList = choiceValues.subList(0, choiceValues.size());
        Collections.sort(sortedList);
        Iterator iterator = sortedList.iterator();*/
      // -> no, already sorted
      final Map<String, Element> result = new HashMap<String, Element>();
      for (XSTypeDefinition type : choiceValues)
      {
         String textValue = type.getName();
         //String textValue = (String) iterator.next();

         if (LOGGER.isDebugEnabled())
            LOGGER.debug("addChoicesForSelectSwitchControl, processing " + textValue);
         final Element item = this.createXFormsItem(xForm, 
                                                    this.createCaption(textValue), 
                                                    textValue);
         choicesElement.appendChild(item);

         /// action in the case

         Element action = xForm.createElementNS(SchemaFormBuilder.XFORMS_NS,
                                                SchemaFormBuilder.XFORMS_NS_PREFIX + "action");
         this.setXFormsId(action);
         item.appendChild(action);

         action.setAttributeNS(SchemaFormBuilder.XMLEVENTS_NS, 
                               SchemaFormBuilder.XMLEVENTS_NS_PREFIX + "event", 
                               "xforms-select");

         Element toggle = xForm.createElementNS(SchemaFormBuilder.XFORMS_NS,
                                                SchemaFormBuilder.XFORMS_NS_PREFIX + "toggle");
         this.setXFormsId(toggle);

         //build the case element
         Element caseElement = xForm.createElementNS(SchemaFormBuilder.XFORMS_NS, 
                                                     SchemaFormBuilder.XFORMS_NS_PREFIX + "case");
         String case_id = this.setXFormsId(caseElement);
         result.put(textValue, caseElement);

         toggle.setAttributeNS(SchemaFormBuilder.XFORMS_NS,
                               SchemaFormBuilder.XFORMS_NS_PREFIX + "case",
                               case_id);

         //toggle.setAttributeNS(SchemaFormBuilder.XFORMS_NS,SchemaFormBuilder.XFORMS_NS_PREFIX + "case",bindIdPrefix + "_" + textValue +"_case");
         action.appendChild(toggle);
      }
      return result;
   }

   private String extractPropertyFromAnnotation(final String namespace, 
                                                final String elementName, 
                                                final XSAnnotation annotation)
   {
      if (annotation == null)
         return null;
      // write annotation to empty doc
      final Document doc = DOMUtil.newDocument(true, false);
      annotation.writeAnnotation(doc, XSAnnotation.W3C_DOM_DOCUMENT);	
      
      final NodeList d = doc.getElementsByTagNameNS(namespace, elementName);
      if (d.getLength() == 0) 
         return null;
      if (d.getLength() > 1)
         LOGGER.warn("expect exactly one value for " + namespace + 
                     ":" + elementName +
                     ". found " + d.getLength());
      final String result = DOMUtil.getTextNodeAsString(d.item(0));
      LOGGER.debug(namespace + ":" + elementName + " = " + result);
      return result;
   }

   public XSParticle findCorrespondingParticleInComplexType(final XSElementDeclaration elDecl) 
   {
      XSComplexTypeDefinition complexType = elDecl.getEnclosingCTDefinition();
      if (complexType == null)
         return null;

      XSParticle particle = complexType.getParticle();
      XSTerm term = particle.getTerm();
      if (! (term instanceof XSModelGroup)) 
         return null;

      XSModelGroup group = (XSModelGroup) term;
      XSObjectList particles = group.getParticles();
      if (particles == null)
         return null;

      for (int i = 0; i < particles.getLength(); i++) 
      {
         XSParticle part = (XSParticle) particles.item(i);
         //test term
         XSTerm thisTerm = part.getTerm();
         if (thisTerm == elDecl)
            return part;
      }
      return null;
   }

   /**
    * finds the minOccurs and maxOccurs of an element declaration
    *
    * @return a table containing minOccurs and MaxOccurs
    */
   public Occurs getOccurance(XSElementDeclaration elDecl) 
   {
      //get occurance on encosing element declaration
      XSParticle particle =
         this.findCorrespondingParticleInComplexType(elDecl);
      Occurs result = new Occurs(particle);

      if (LOGGER.isDebugEnabled())
         LOGGER.debug("getOccurance for " + elDecl.getName() + 
                      ", " + result);
      return result;
   }

   private void addAnyType(final Document xForm,
                           final Element modelSection,
                           final Element formSection,
                           final XSModel schema,
                           final XSTypeDefinition controlType,
                           final XSElementDeclaration owner,
                           final String pathToRoot) 
   {
      this.addSimpleType(xForm,
                         modelSection,
                         formSection,
                         schema,
                         controlType,
                         owner.getName(),
                         owner,
                         pathToRoot,
                         this.getOccurance(owner));
   }

   private void addAttributeSet(final Document xForm,
                                final Element modelSection,
                                final Element defaultInstanceElement,
                                final Element formSection,
                                final XSModel schema,
                                final XSComplexTypeDefinition controlType,
                                final XSElementDeclaration owner,
                                final String pathToRoot,
                                final boolean checkIfExtension) 
   {
      XSObjectList attrUses = controlType.getAttributeUses();

      if (attrUses == null)
         return;
      for (int i = 0; i < attrUses.getLength(); i++) 
      {
         final XSAttributeUse currentAttributeUse = (XSAttributeUse)attrUses.item(i);
         final XSAttributeDeclaration currentAttribute =
            currentAttributeUse.getAttrDeclaration();
		
         String attributeName = currentAttributeUse.getName();
         if (attributeName == null || attributeName.length() == 0)
            attributeName = currentAttributeUse.getAttrDeclaration().getName();
	    
         //test if extended !
         if (checkIfExtension && 
             this.doesAttributeComeFromExtension(currentAttributeUse, controlType)) 
         {
            if (LOGGER.isDebugEnabled()) 
            {
               LOGGER.debug("This attribute comes from an extension: recopy form controls. \n Model section: ");
               DOMUtil.prettyPrintDOM(modelSection);
            }
		
            //find the existing bind Id
            //(modelSection is the enclosing bind of the element)
            final NodeList binds = modelSection.getElementsByTagNameNS(SchemaFormBuilder.XFORMS_NS, "bind");
            String bindId = null;
            for (int j = 0; j < binds.getLength() && bindId == null; j++) 
            {
               Element bind = (Element) binds.item(j);
               String nodeset = bind.getAttributeNS(SchemaFormBuilder.XFORMS_NS, "nodeset");
               if (nodeset != null) 
               {
                  //remove "@" in nodeset
                  String name = nodeset.substring(1);
                  if (name.equals(attributeName))
                     bindId = bind.getAttributeNS(SchemaFormBuilder.XFORMS_NS, "id");
               }
            }
		
            //find the control
            Element control = null;
            if (bindId != null) 
            {
               if (LOGGER.isDebugEnabled())
                  LOGGER.debug("bindId found: " + bindId);
		    
               JXPathContext context = JXPathContext.newContext(formSection.getOwnerDocument());
               final Pointer pointer = 
                  context.getPointer("//*[@" + SchemaFormBuilder.XFORMS_NS_PREFIX + 
                                     "bind='" + bindId + "']");
               if (pointer != null)
                  control = (Element)pointer.getNode();
            }
		
            //copy it
            if (control == null)
               LOGGER.warn("Corresponding control not found");
            else 
            {
               Element newControl = (Element) control.cloneNode(true);
               //set new Ids to XForm elements
               this.resetXFormIds(newControl);
		    
               formSection.appendChild(newControl);
            }
         } 
         else 
         {
            defaultInstanceElement.setAttributeNS(this.targetNamespace,
                                                  // XXXarielb - i probably need the prefix here i.e. "alf:" + attributeName
                                                  attributeName,
                                                  (currentAttributeUse.getConstraintType() == XSConstants.VC_NONE
                                                   ? null
                                                   : currentAttributeUse.getConstraintValue()));
            final String newPathToRoot =
               (pathToRoot == null || pathToRoot.length() == 0
                ? "@" + currentAttribute.getName()
                : (pathToRoot.endsWith("/")
                   ? pathToRoot + "@" + currentAttribute.getName()
                   : pathToRoot + "/@" + currentAttribute.getName()));
		
            this.addSimpleType(xForm,
                               modelSection,
                               formSection,
                               schema,
                               currentAttribute.getTypeDefinition(),
                               currentAttributeUse,
                               newPathToRoot);
         }
      }
   }

   private void addComplexType(final Document xForm,
                               final Element modelSection,
                               final Element defaultInstanceElement,
                               final Element formSection,
                               final XSModel schema,
                               final XSComplexTypeDefinition controlType,
                               final XSElementDeclaration owner,
                               final String pathToRoot,
                               boolean relative,
                               final boolean checkIfExtension) {

      if (controlType == null) 
      {
         if (LOGGER.isDebugEnabled())
            LOGGER.debug("addComplexType: control type is null for pathToRoot="
                         + pathToRoot);
         return;
      }

      if (LOGGER.isDebugEnabled()) {
         LOGGER.debug("addComplexType for " + controlType.getName());
         if (owner != null)
            LOGGER.debug("	owner=" + owner.getName());
      }

      // add a group node and recurse
      //
      Element groupElement =
         createGroup(xForm, modelSection, formSection, owner);
      Element groupWrapper = groupElement;
	
      if (groupElement != modelSection) 
         groupWrapper = this.wrapper.createGroupContentWrapper(groupElement);
	
      final Occurs o = this.getOccurance(owner);
      final Element repeatSection = this.addRepeatIfNecessary(xForm,
                                                              modelSection,
                                                              groupWrapper,
                                                              controlType,
                                                              o,
                                                              pathToRoot);
      Element repeatContentWrapper = repeatSection;
	
      if (repeatSection != groupWrapper) 
      { 
         // we have a repeat
         repeatContentWrapper = this.wrapper.createGroupContentWrapper(repeatSection);
         relative = true;
      }
	
      this.addComplexTypeChildren(xForm,
                                  modelSection,
                                  defaultInstanceElement,
                                  repeatContentWrapper,
                                  schema,
                                  controlType,
                                  owner,
                                  pathToRoot,
                                  relative,
                                  checkIfExtension);
   }

   private void addComplexTypeChildren(final Document xForm,
                                       Element modelSection,
                                       final Element defaultInstanceElement,
                                       final Element formSection,
                                       final XSModel schema,
                                       final XSComplexTypeDefinition controlType,
                                       final XSElementDeclaration owner,
                                       String pathToRoot,
                                       final boolean relative,
                                       final boolean checkIfExtension) {

      if (controlType == null)
         return;

      if (LOGGER.isDebugEnabled()) 
      {
         LOGGER.debug("addComplexTypeChildren for " + controlType.getName());
         if (owner != null)
            LOGGER.debug("	owner=" + owner.getName());
      }

      if (controlType.getContentType() == XSComplexTypeDefinition.CONTENTTYPE_MIXED || 
          (controlType.getContentType() == XSComplexTypeDefinition.CONTENTTYPE_SIMPLE && 
           controlType.getAttributeUses() != null && 
           controlType.getAttributeUses().getLength() > 0)) 
      {
         XSTypeDefinition base = controlType.getBaseType();
         if (LOGGER.isDebugEnabled())
            LOGGER.debug("	Control type is mixed . base type=" + base.getName());

         if (base != null && base != controlType) 
         {
            if (base.getTypeCategory() == XSTypeDefinition.SIMPLE_TYPE) 
            {
               this.addSimpleType(xForm,
                                  modelSection,
                                  formSection,
                                  schema,
                                  (XSSimpleTypeDefinition) base,
                                  owner,
                                  pathToRoot);
            }
            else
               LOGGER.warn("addComplexTypeChildren for mixed type with basic type complex !");
         }
      } 
      else if (LOGGER.isDebugEnabled())
         LOGGER.debug("	Content type = " + controlType.getContentType());


      // check for compatible subtypes
      // of controlType.
      // add a type switch if there are any
      // compatible sub-types (i.e. anything
      // that derives from controlType)
      // add child elements
      if (relative) 
      {
         pathToRoot = "";

         //modelSection: find the last element put in the modelSection = bind
         modelSection = DOMUtil.getLastChildElement(modelSection);
      }

      //attributes
      this.addAttributeSet(xForm,
                           modelSection,
                           defaultInstanceElement,
                           formSection,
                           schema,
                           controlType,
                           owner,
                           pathToRoot,
                           checkIfExtension);

      //process group
      final XSParticle particle = controlType.getParticle();
      if (particle != null) 
      {
         final XSTerm term = particle.getTerm();
         if (! (term instanceof XSModelGroup)) 
         {
            if (LOGGER.isDebugEnabled())
               LOGGER.debug("	Particle of " + controlType.getName() + 
                            " is not a group: " + term.getClass().getName());
         }
         else
         {
            if (LOGGER.isDebugEnabled())
               LOGGER.debug("	Particle of " + controlType.getName() + 
                            " is a group --->");

            XSModelGroup group = (XSModelGroup) term;
            //call addGroup on this group
            this.addGroup(xForm,
                          modelSection,
                          defaultInstanceElement,
                          formSection,
                          schema,
                          group,
                          controlType,
                          owner,
                          pathToRoot,
                          new Occurs(particle),
                          checkIfExtension);

         }
      }

      if (LOGGER.isDebugEnabled())
         LOGGER.debug("--->end of addComplexTypeChildren for " + controlType.getName());
   }

   /**
    * add an element to the XForms document: the bind + the control
    * (only the control if "withBind" is false)
    */
   private void addElement(final Document xForm,
                           final Element modelSection,
                           final Element defaultInstanceElement,
                           final Element formSection,
                           final XSModel schema,
                           final XSElementDeclaration elementDecl,
                           XSTypeDefinition controlType,
                           final String pathToRoot) {

      if (controlType == null) 
      {
         // TODO!!! Figure out why this happens... for now just warn...
         // seems to happen when there is an element of type IDREFS
         LOGGER.warn("WARNING!!! controlType is null for " + elementDecl + 
                     ", " + elementDecl.getName());
         return;
      }

      switch (controlType.getTypeCategory()) 
      {
      case XSTypeDefinition.SIMPLE_TYPE:
      {
         this.addSimpleType(xForm,
                            modelSection,
                            formSection,
                            schema,
                            (XSSimpleTypeDefinition) controlType,
                            elementDecl,
                            pathToRoot);
         break;
      }
      case XSTypeDefinition.COMPLEX_TYPE:
      {
         final String typeName = controlType.getName();	    
         if ("anyType".equals(typeName)) 
         {
            this.addAnyType(xForm,
                            modelSection,
                            formSection,
                            schema,
                            (XSComplexTypeDefinition) controlType,
                            elementDecl,
                            pathToRoot);
            break;
         }
		
         // find the types which are compatible(derived from) the parent type.
         //
         // This is used if we encounter a XML Schema that permits the xsi:type
         // attribute to specify subtypes for the element.
         //
         // For example, the <address> element may be typed to permit any of
         // the following scenarios:
         // <address xsi:type="USAddress">
         // </address>
         // <address xsi:type="CanadianAddress">
         // </address>
         // <address xsi:type="InternationalAddress">
         // </address>
         //
         // What we want to do is generate an XForm' switch element with cases
         // representing any valid non-abstract subtype.
         //
         // <xforms:select1 xforms:bind="xsi_type_13"
         //		  <xforms:label>Address</xforms:label>
         //        <xforms:choices>
         //                <xforms:item>
         //                        <xforms:label>US Address Type</xforms:label>
         //                        <xforms:value>USAddressType</xforms:value>
         //                        <xforms:action ev:event="xforms-select">
         //                                <xforms:toggle xforms:case="USAddressType-case"/>
         //                        </xforms:action>
         //                </xforms:item>
         //                <xforms:item>
         //                        <xforms:label>Canadian Address Type</xforms:label>
         //                        <xforms:value>CanadianAddressType</xforms:value>
         //                        <xforms:action ev:event="xforms-select">
         //                                <xforms:toggle xforms:case="CanadianAddressType-case"/>
         //                        </xforms:action>
         //                </xforms:item>
         //                <xforms:item>
         //                        <xforms:label>International Address Type</xforms:label>
         //                        <xforms:value>InternationalAddressType</xforms:value>
         //                        <xforms:action ev:event="xforms-select">
         //                                <xforms:toggle xforms:case="InternationalAddressType-case"/>
         //                        </xforms:action>
         //                </xforms:item>
         //
         //          </xforms:choices>
         // <xforms:select1>
         // <xforms:trigger>
         //	<xforms:label>validate Address type</xforms:label>
         //	<xforms:action>
         //		<xforms:dispatch id="dispatcher" xforms:name="xforms-activate" xforms:target="select1_0"/>
         //	</xforms:action>
         //</xforms:trigger>
         //
         // <xforms:switch id="address_xsi_type_switch">
         //      <xforms:case id="USAddressType-case" selected="false">
         //          <!-- US Address Type sub-elements here-->
         //      </xforms:case>
         //      <xforms:case id="CanadianAddressType-case" selected="false">
         //          <!-- US Address Type sub-elements here-->
         //      </xforms:case>
         //      ...
         // </xforms:switch>
         //
         //   + change bindings to add:
         //	- a bind for the "@xsi:type" attribute
         //	- for each possible element that can be added through the use of an inheritance, add a "relevant" attribute:
         //	ex: xforms:relevant="../@xsi:type='USAddress'"
	    
         // look for compatible types
         //

         boolean relative = true;
         if (typeName != null) 
         {
            final TreeSet<XSTypeDefinition> compatibleTypes = 
               this.typeTree.get(controlType.getName());
            if (compatibleTypes == null) 
            {
               if (LOGGER.isDebugEnabled())
                  LOGGER.debug("No compatible type found for " + typeName);
            }
            else
            {
               relative = false;
		    
               if (LOGGER.isDebugEnabled()) 
               {
                  LOGGER.debug("compatible types for " + typeName + ":");
                  for (XSTypeDefinition compType : compatibleTypes) 
                  {
                     LOGGER.debug("          compatible type name=" + compType.getName());
                  }
               }
		    
               Element control = xForm.createElementNS(SchemaFormBuilder.XFORMS_NS,
                                                       SchemaFormBuilder.XFORMS_NS_PREFIX + 
                                                       "select1");
               String select1_id = this.setXFormsId(control);
		    
               Element choices = xForm.createElementNS(SchemaFormBuilder.XFORMS_NS,
                                                       SchemaFormBuilder.XFORMS_NS_PREFIX + "choices");
               this.setXFormsId(choices);
		    
               //get possible values
               List<XSTypeDefinition> enumValues = new LinkedList<XSTypeDefinition>();
               //add the type (if not abstract)
               if (!((XSComplexTypeDefinition) controlType).getAbstract())
                  enumValues.add(controlType);
		    
               //add compatible types
               enumValues.addAll(compatibleTypes);
		    
               if (enumValues.size() == 1) 
               {
                  // only one compatible type, set the controlType value
                  // and fall through
                  //
                  //controlType = schema.getTypeDefinition((String)enumValues.get(0),
                  //				       this.targetNamespace);
                  controlType = enumValues.get(0);
               }
               else if (enumValues.size() > 1) 
               {
                  String caption = createCaption(elementDecl.getName() + " Type");
                  Element controlCaption = 
                     xForm.createElementNS(SchemaFormBuilder.XFORMS_NS,
                                           SchemaFormBuilder.XFORMS_NS_PREFIX + "label");
                  control.appendChild(controlCaption);
                  this.setXFormsId(controlCaption);
                  controlCaption.appendChild(xForm.createTextNode(caption));
			
                  // multiple compatible types for this element exist
                  // in the schema - allow the user to choose from
                  // between compatible non-abstract types
                  Element bindElement = xForm.createElementNS(SchemaFormBuilder.XFORMS_NS,
                                                              SchemaFormBuilder.XFORMS_NS_PREFIX + "bind");
                  String bindId = this.setXFormsId(bindElement);
			
                  bindElement.setAttributeNS(SchemaFormBuilder.XFORMS_NS,
                                             SchemaFormBuilder.XFORMS_NS_PREFIX + "nodeset",
                                             pathToRoot + "/@xsi:type");
			
                  modelSection.appendChild(bindElement);
                  control.setAttributeNS(SchemaFormBuilder.XFORMS_NS,
                                         SchemaFormBuilder.XFORMS_NS_PREFIX + "bind",
                                         bindId);
			
                  //add the "element" bind, in addition
                  Element bindElement2 = xForm.createElementNS(SchemaFormBuilder.XFORMS_NS,
                                                               SchemaFormBuilder.XFORMS_NS_PREFIX + "bind");
                  String bindId2 = this.setXFormsId(bindElement2);
                  bindElement2.setAttributeNS(SchemaFormBuilder.XFORMS_NS,
                                              SchemaFormBuilder.XFORMS_NS_PREFIX + "nodeset",
                                              pathToRoot);
			
                  modelSection.appendChild(bindElement2);
			
                  control.setAttributeNS(SchemaFormBuilder.XFORMS_NS,
                                         SchemaFormBuilder.XFORMS_NS_PREFIX + "appearance",
                                         (enumValues.size() < Long.parseLong(getProperty(SELECTONE_LONG_LIST_SIZE_PROP))
                                          ? getProperty(SELECTONE_UI_CONTROL_SHORT_PROP)
                                          : getProperty(SELECTONE_UI_CONTROL_LONG_PROP)));

                  if (enumValues.size() >= Long.parseLong(getProperty(SELECTONE_LONG_LIST_SIZE_PROP)))
                  {
                     // add the "Please select..." instruction item for the combobox
                     // and set the isValid attribute on the bind element to check for the "Please select..."
                     // item to indicate that is not a valid value
                     //
                     String pleaseSelect = "[Select1 " + caption + "]";
                     final Element item = this.createXFormsItem(xForm, pleaseSelect, pleaseSelect);
                     choices.appendChild(item);
			    
                     // not(purchaseOrder/state = '[Choose State]')
                     //String isValidExpr = "not(" + bindElement.getAttributeNS(SchemaFormBuilder.XFORMS_NS, "nodeset") + " = '" + pleaseSelect + "')";
                     // ->no, not(. = '[Choose State]')
                     String isValidExpr = "not( . = '" + pleaseSelect + "')";
			    
                     //check if there was a constraint
                     String constraint = bindElement.getAttributeNS(SchemaFormBuilder.XFORMS_NS, "constraint");
			    
                     constraint = (constraint != null && constraint.length() != 0
                                   ? constraint + " && " + isValidExpr
                                   : isValidExpr);
			    
                     bindElement.setAttributeNS(SchemaFormBuilder.XFORMS_NS,
                                                SchemaFormBuilder.XFORMS_NS_PREFIX
                                                + "constraint",
                                                constraint);
                  }

                  Element choicesControlWrapper = this.wrapper.createControlsWrapper(choices);
                  control.appendChild(choicesControlWrapper);
			
                  Element controlWrapper = this.wrapper.createControlsWrapper(control);
                  formSection.appendChild(controlWrapper);
			
                  /////////////////                                      ///////////////
                  // add content to select1
                  final Map<String, Element> caseTypes = 
                     this.addChoicesForSelectSwitchControl(xForm, choices, enumValues);
			
                  /////////////////
                  //add a trigger for this control (is there a way to not need it ?)
                  Element trigger = xForm.createElementNS(SchemaFormBuilder.XFORMS_NS,
                                                          SchemaFormBuilder.XFORMS_NS_PREFIX + "trigger");
                  formSection.appendChild(trigger);
                  this.setXFormsId(trigger);
                  Element label_trigger = xForm.createElementNS(SchemaFormBuilder.XFORMS_NS,
                                                                SchemaFormBuilder.XFORMS_NS_PREFIX + "label");
                  this.setXFormsId(label_trigger);
                  trigger.appendChild(label_trigger);
                  String trigger_caption = createCaption("validate choice");
                  label_trigger.appendChild(xForm.createTextNode(trigger_caption));
                  Element action_trigger = xForm.createElementNS(SchemaFormBuilder.XFORMS_NS,
                                                                 SchemaFormBuilder.XFORMS_NS_PREFIX + "action");
                  this.setXFormsId(action_trigger);
                  trigger.appendChild(action_trigger);
                  Element dispatch_trigger = xForm.createElementNS(SchemaFormBuilder.XFORMS_NS,
                                                                   SchemaFormBuilder.XFORMS_NS_PREFIX + "dispatch");
                  this.setXFormsId(dispatch_trigger);
                  action_trigger.appendChild(dispatch_trigger);
                  dispatch_trigger.setAttributeNS(SchemaFormBuilder.XFORMS_NS,
                                                  SchemaFormBuilder.XFORMS_NS_PREFIX + "name",
                                                  "DOMActivate");
                  dispatch_trigger.setAttributeNS(SchemaFormBuilder.XFORMS_NS,
                                                  SchemaFormBuilder.XFORMS_NS_PREFIX + "target",
                                                  select1_id);
			
                  /////////////////
                  //add switch
                  Element switchElement = xForm.createElementNS(SchemaFormBuilder.XFORMS_NS,
                                                                SchemaFormBuilder.XFORMS_NS_PREFIX + "switch");
                  this.setXFormsId(switchElement);
			
                  Element switchControlWrapper =
                     this.wrapper.createControlsWrapper(switchElement);
                  formSection.appendChild(switchControlWrapper);
                  //formSection.appendChild(switchElement);
			
                  /////////////// add this type //////////////
                  Element firstCaseElement = caseTypes.get(controlType.getName());
                  switchElement.appendChild(firstCaseElement);
                  this.addComplexType(xForm,
                                      modelSection,
                                      defaultInstanceElement,
                                      firstCaseElement,
                                      schema,
                                      (XSComplexTypeDefinition)controlType,
                                      elementDecl,
                                      pathToRoot,
                                      true,
                                      false);
			
                  /////////////// add sub types //////////////
                  // add each compatible type within
                  // a case statement
                  for (XSTypeDefinition type : compatibleTypes) 
                  {
                     /*String compatibleTypeName = (String) it.next();
                     //WARNING: order of parameters inversed from the doc for 2.6.0 !!!
                     XSTypeDefinition type =getSchema().getTypeDefinition(
                     compatibleTypeName,
                     targetNamespace);*/
                     String compatibleTypeName = type.getName();
			    
                     if (LOGGER.isDebugEnabled()) 
                        LOGGER.debug(type == null
                                     ? (">>>addElement: compatible type is null!! type=" + 
                                        compatibleTypeName + ", targetNamespace=" + this.targetNamespace)
                                     : ("   >>>addElement: adding compatible type " + type.getName()));
			    
                     if (type != null && 
                         type.getTypeCategory() == XSTypeDefinition.COMPLEX_TYPE) 
                     {
				
                        //Element caseElement = (Element) xForm.createElementNS(SchemaFormBuilder.XFORMS_NS,SchemaFormBuilder.XFORMS_NS_PREFIX + "case");
                        //caseElement.setAttributeNS(SchemaFormBuilder.XFORMS_NS,SchemaFormBuilder.XFORMS_NS_PREFIX + "id",bindId + "_" + type.getName() +"_case");
                        //String case_id=this.setXFormsId(caseElement);
                        Element caseElement = caseTypes.get(type.getName());
                        switchElement.appendChild(caseElement);
				
                        this.addComplexType(xForm,
                                            modelSection,
                                            defaultInstanceElement,
                                            caseElement,
                                            schema,
                                            (XSComplexTypeDefinition) type,
                                            elementDecl,
                                            pathToRoot,
                                            true,
                                            true);
				
                        //////
                        // modify bind to add a "relevant" attribute that checks the value of @xsi:type
                        //
                        if (LOGGER.isDebugEnabled())
                           DOMUtil.prettyPrintDOM(bindElement2);
                        NodeList binds = bindElement2.getElementsByTagNameNS(SchemaFormBuilder.XFORMS_NS, "bind");
                        Element thisBind = null;
                        for (int i = 0; i < binds.getLength() && thisBind == null; i++) 
                        {
                           Element subBind = (Element) binds.item(i);
                           String name = subBind.getAttributeNS(SchemaFormBuilder.XFORMS_NS, "nodeset");
				    
                           if (LOGGER.isDebugEnabled())
                              LOGGER.debug("Testing sub-bind with nodeset " + name);
				    
                           if (this.isElementDeclaredIn(name, (XSComplexTypeDefinition) type, false) || 
                               this.isAttributeDeclaredIn(name, (XSComplexTypeDefinition) type, false))
                           {
                              if (LOGGER.isDebugEnabled())
                                 LOGGER.debug("Element/Attribute " + name + 
                                              " declared in type " + type.getName() + 
                                              ": adding relevant attribute");

                              //test sub types of this type
                              final TreeSet<XSTypeDefinition> subCompatibleTypes = this.typeTree.get(type.getName());
                              //TreeSet subCompatibleTypes = (TreeSet) typeTree.get(type);
					
                              String newRelevant = null;
                              if (subCompatibleTypes == null || subCompatibleTypes.isEmpty()) 
                              {
                                 //just add ../@xsi:type='type'
                                 newRelevant = "../@xsi:type='" + type.getName() + "'";
                              }
                              else 
                              {
                                 //add ../@xsi:type='type' or ../@xsi:type='otherType' or ...
                                 newRelevant = "../@xsi:type='" + type.getName() + "'";
                                 for (XSTypeDefinition otherType : subCompatibleTypes) 
                                 {
                                    String otherTypeName = otherType.getName();
                                    newRelevant = newRelevant + " or ../@xsi:type='" + otherTypeName + "'";
                                 }
                              }

                              //change relevant attribute
                              String relevant = subBind.getAttributeNS(SchemaFormBuilder.XFORMS_NS, "relevant");
                              if (relevant != null && relevant.length() != 0) 
                                 newRelevant = ("(" + relevant + 
                                                ") and " + newRelevant);
                              if (newRelevant != null && newRelevant.length() != 0)
                                 subBind.setAttributeNS(SchemaFormBuilder.XFORMS_NS, 
                                                        SchemaFormBuilder.XFORMS_NS_PREFIX + "relevant", 
                                                        newRelevant);
                           }
                        }
                     }
                  }

                  /*if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug(
                    "###addElement for derived type: bind created:");
                    DOMUtil.prettyPrintDOM(bindElement2);
                    }*/

                  // we're done
                  //
                  break;

               } 
            }
            //name not null but no compatibleType?
            relative = true;
         }

         if (!relative) //create the bind in case it is a repeat
         {
            if (LOGGER.isDebugEnabled()) 
               LOGGER.debug("addElement: bind is not relative for "
                            + elementDecl.getName());
         }
         else
         {
            if (LOGGER.isDebugEnabled())
               LOGGER.debug(">>>Adding empty bind for " + typeName);

            // create the <xforms:bind> element and add it to the model.
            Element bindElement =
               xForm.createElementNS(SchemaFormBuilder.XFORMS_NS,
                                     SchemaFormBuilder.XFORMS_NS_PREFIX + "bind");
            String bindId = this.setXFormsId(bindElement);
            bindElement.setAttributeNS(SchemaFormBuilder.XFORMS_NS,
                                       SchemaFormBuilder.XFORMS_NS_PREFIX + "nodeset",
                                       pathToRoot);

            modelSection.appendChild(bindElement);
         } 

         //addComplexType(xForm,modelSection, formSection,(ComplexType)controlType,elementDecl,pathToRoot, relative);
         this.addComplexType(xForm,
                             modelSection,
                             defaultInstanceElement,
                             formSection,
                             schema,
                             (XSComplexTypeDefinition)controlType,
                             elementDecl,
                             pathToRoot,
                             true,
                             false);
			
         break;
      }
	
      default : // TODO: add wildcard support
         LOGGER.warn("\nWARNING!!! - Unsupported type [" + elementDecl.getType() +
                     "] for node [" + controlType.getName() + "]");
      }
   }
    
   /**
    * check that the element defined by this name is declared directly in the type
    */
   private boolean isElementDeclaredIn(String name, 
                                       XSComplexTypeDefinition type, 
                                       boolean recursive) 
   {
      boolean found = false;

      if (LOGGER.isDebugEnabled())
         LOGGER.debug("isElement " + name + " declared in " + type.getName());

      //test if extension + declared in parent + not recursive -> NOK
      if (!recursive && type.getDerivationMethod() == XSConstants.DERIVATION_EXTENSION) 
      {
         XSComplexTypeDefinition parent = (XSComplexTypeDefinition) type.getBaseType();
         if (LOGGER.isDebugEnabled())
            LOGGER.debug("testing if it is not on parent " + parent.getName());
         if (this.isElementDeclaredIn(name, parent, true))
            return false;
      }

      XSParticle particle = type.getParticle();
      if (particle != null) 
      {
         XSTerm term = particle.getTerm();
         if (term instanceof XSModelGroup) 
         {
            XSModelGroup group = (XSModelGroup) term;
            found = this.isElementDeclaredIn(name, group);
         }
      }

      if (LOGGER.isDebugEnabled())
         LOGGER.debug("isElement " + name + 
                      " declared in " + type.getName() + ": " + found);

      return found;
   }

   /**
    * private recursive method called by isElementDeclaredIn(String name, XSComplexTypeDefinition type)
    */
   private boolean isElementDeclaredIn(String name, XSModelGroup group) 
   {
      if (LOGGER.isDebugEnabled())
         LOGGER.debug("isElement " + name + " declared in group " + group.getName());

      boolean found = false;
      XSObjectList particles = group.getParticles();
      for (int i = 0; i < particles.getLength(); i++) 
      {
         XSParticle subPart = (XSParticle)particles.item(i);
         XSTerm subTerm = subPart.getTerm();
         if (subTerm instanceof XSElementDeclaration) 
         {
            XSElementDeclaration elDecl = (XSElementDeclaration) subTerm;
            if (name.equals(elDecl.getName()))
               found = true;
         } 
         else if (subTerm instanceof XSModelGroup)
         {
            found = this.isElementDeclaredIn(name, (XSModelGroup) subTerm);
         }
      }

      if (LOGGER.isDebugEnabled())
         LOGGER.debug("isElement " + name + " declared in group " + group.getName() + ": " + found);
      return found;
   }

   private boolean doesElementComeFromExtension(XSElementDeclaration element, 
                                                XSComplexTypeDefinition controlType) 
   {
      if (LOGGER.isDebugEnabled())
         LOGGER.debug("doesElementComeFromExtension for " + element.getName() + " and controlType=" + controlType.getName());
      boolean comesFromExtension = false;
      if (controlType.getDerivationMethod() == XSConstants.DERIVATION_EXTENSION) {
         XSTypeDefinition baseType = controlType.getBaseType();
         if (baseType.getTypeCategory() == XSTypeDefinition.COMPLEX_TYPE) {
            XSComplexTypeDefinition complexType = (XSComplexTypeDefinition) baseType;
            if (this.isElementDeclaredIn(element.getName(), complexType, true)) {
               if (LOGGER.isDebugEnabled())
                  LOGGER.debug("doesElementComeFromExtension: yes");
               comesFromExtension = true;
            } else { //recursive call
               if (LOGGER.isDebugEnabled())
                  LOGGER.debug("doesElementComeFromExtension: recursive call on previous level");
               comesFromExtension = this.doesElementComeFromExtension(element, complexType);
            }
         }
      }
      return comesFromExtension;
   }

   /**
    * check that the element defined by this name is declared directly in the type
    */
   private boolean isAttributeDeclaredIn(XSAttributeUse attr, XSComplexTypeDefinition type, boolean recursive) 
   {
      boolean found = false;

      if (LOGGER.isDebugEnabled())
         LOGGER.debug("is Attribute " + attr.getAttrDeclaration().getName() + " declared in " + type.getName());

      //check on parent if not recursive
      if (!recursive && type.getDerivationMethod() == XSConstants.DERIVATION_EXTENSION) 
      {
         XSComplexTypeDefinition parent = (XSComplexTypeDefinition) type.getBaseType();
         if (LOGGER.isDebugEnabled())
            LOGGER.debug("testing if it is not on parent " + parent.getName());
         if (this.isAttributeDeclaredIn(attr, parent, true))
            return false;
      }

      //check on this type  (also checks recursively)
      final XSObjectList attrs = type.getAttributeUses();
      for (int i = 0; i < attrs.getLength() && !found; i++) 
      {
         XSAttributeUse anAttr = (XSAttributeUse)attrs.item(i);
         if (anAttr == attr)
            found = true;
      }

      if (LOGGER.isDebugEnabled())
         LOGGER.debug("is Attribute " + attr.getName() + " declared in " + type.getName() + ": " + found);

      return found;
   }

   /**
    * check that the element defined by this name is declared directly in the type
    * -> idem with string
    */
   private boolean isAttributeDeclaredIn(String attrName, XSComplexTypeDefinition type, boolean recursive) 
   {
      boolean found = false;

      if (LOGGER.isDebugEnabled())
         LOGGER.debug("is Attribute " + attrName + " declared in " + type.getName());

      if (attrName.startsWith("@"))
         attrName = attrName.substring(1);

      //check on parent if not recursive
      if (!recursive && type.getDerivationMethod() == XSConstants.DERIVATION_EXTENSION) 
      {
         XSComplexTypeDefinition parent = (XSComplexTypeDefinition) type.getBaseType();
         if (LOGGER.isDebugEnabled())
            LOGGER.debug("testing if it is not on parent " + parent.getName());
         if (this.isAttributeDeclaredIn(attrName, parent, true))
            return false;
      }

      //check on this type (also checks recursively)
      final XSObjectList attrs = type.getAttributeUses();
      for (int i = 0; i < attrs.getLength() && !found; i++) 
      {
         final XSAttributeUse anAttr = (XSAttributeUse) attrs.item(i);
         if (anAttr != null) 
         {
            String name = anAttr.getName();
            if (name == null || name.length() == 0)
               name = anAttr.getAttrDeclaration().getName();
            if (attrName.equals(name))
               found = true;
         }
      }

      if (LOGGER.isDebugEnabled())
         LOGGER.debug("is Attribute " + attrName + " declared in " + type.getName() + ": " + found);

      return found;
   }

   private boolean doesAttributeComeFromExtension(XSAttributeUse attr, XSComplexTypeDefinition controlType) 
   {
      if (LOGGER.isDebugEnabled())
         LOGGER.debug("doesAttributeComeFromExtension for " + attr.getAttrDeclaration().getName() + 
                      " and controlType=" + controlType.getName());
      boolean comesFromExtension = false;
      if (controlType.getDerivationMethod() == XSConstants.DERIVATION_EXTENSION) {
         XSTypeDefinition baseType = controlType.getBaseType();
         if (baseType.getTypeCategory() == XSTypeDefinition.COMPLEX_TYPE) {
            XSComplexTypeDefinition complexType = (XSComplexTypeDefinition) baseType;
            if (this.isAttributeDeclaredIn(attr, complexType, true)) {
               if (LOGGER.isDebugEnabled())
                  LOGGER.debug("doesAttributeComeFromExtension: yes");
               comesFromExtension = true;
            } else { //recursive call
               if (LOGGER.isDebugEnabled())
                  LOGGER.debug("doesAttributeComeFromExtension: recursive call on previous level");
               comesFromExtension = this.doesAttributeComeFromExtension(attr, complexType);
            }
         }
      }
      return comesFromExtension;
   }

   /**
    * checkIfExtension: if false, addElement is called wether it is an extension or not
    * if true, if it is an extension, element is recopied (and no additional bind)
    */
   private void addGroup(final Document xForm,
                         final Element modelSection,
                         final Element defaultInstanceElement,
                         final Element formSection,
                         final XSModel schema,
                         final XSModelGroup group,
                         final XSComplexTypeDefinition controlType,
                         final XSElementDeclaration owner,
                         final String pathToRoot,
                         final Occurs o,
                         final boolean checkIfExtension) 
   {
      if (group == null) 
         return;

      final Element repeatSection = 
         this.addRepeatIfNecessary(xForm,
                                   modelSection,
                                   formSection,
                                   owner.getTypeDefinition(),
                                   o,
                                   pathToRoot);
      Element repeatContentWrapper = repeatSection;
	
      if (repeatSection != formSection) 
      {
         //selector -> no more needed?
         //this.addSelector(xForm, repeatSection);
         //group wrapper
         repeatContentWrapper =
            this.wrapper.createGroupContentWrapper(repeatSection);
      }

      if (LOGGER.isDebugEnabled())
         LOGGER.debug("addGroup from owner=" + owner.getName() + 
                      " and controlType=" + controlType.getName());

      final XSObjectList particles = group.getParticles();
      for (int counter = 0; counter < particles.getLength(); counter++) 
      {
         final XSParticle currentNode = (XSParticle)particles.item(counter);
         XSTerm term = currentNode.getTerm();

         if (LOGGER.isDebugEnabled())
            LOGGER.debug("	: next term = " + term.getName());

         final Occurs childOccurs = new Occurs(currentNode);

         if (term instanceof XSModelGroup) 
         {
            
            if (LOGGER.isDebugEnabled())
               LOGGER.debug("	term is a group");
            
            this.addGroup(xForm,
                          modelSection,
                          defaultInstanceElement,
                          repeatContentWrapper,
                          schema,
                          ((XSModelGroup) term),
                          controlType,
                          owner,
                          pathToRoot,
                          childOccurs,
                          checkIfExtension);
         } 
         else if (term instanceof XSElementDeclaration) 
         {
            XSElementDeclaration element = (XSElementDeclaration) term;

            if (LOGGER.isDebugEnabled())
               LOGGER.debug("	term is an element declaration: "
                            + term.getName());

            //special case for types already added because used in an extension
            //do not add it when it comes from an extension !!!
            //-> make a copy from the existing form control
            if (checkIfExtension && 
                this.doesElementComeFromExtension(element, controlType)) 
            {
               if (LOGGER.isDebugEnabled()) 
               {
                  LOGGER.debug("This element comes from an extension: recopy form controls.\n Model Section=");
                  DOMUtil.prettyPrintDOM(modelSection);
               }

               //find the existing bind Id
               //(modelSection is the enclosing bind of the element)
               NodeList binds = modelSection.getElementsByTagNameNS(SchemaFormBuilder.XFORMS_NS, "bind");
               String bindId = null;
               for (int i = 0; i < binds.getLength() && bindId == null; i++) 
               {
                  Element bind = (Element)binds.item(i);
                  String nodeset = bind.getAttributeNS(SchemaFormBuilder.XFORMS_NS, "nodeset");
                  if (nodeset != null && nodeset.equals(element.getName()))
                     bindId = bind.getAttributeNS(SchemaFormBuilder.XFORMS_NS, "id");
               }

               //find the control
               Element control = null;
               if (bindId != null) 
               {
                  if (LOGGER.isDebugEnabled())
                     LOGGER.debug("bindId found: " + bindId);

                  final JXPathContext context = 
                     JXPathContext.newContext(formSection.getOwnerDocument());
                  final Pointer pointer = 
                     context.getPointer("//*[@" + SchemaFormBuilder.XFORMS_NS_PREFIX + 
                                        "bind='" + bindId + "']");
                  if (pointer != null)
                     control = (Element) pointer.getNode();
               }

               //copy it
               if (control == null)
                  LOGGER.warn("Corresponding control not found");
               else 
               {
                  Element newControl = (Element)control.cloneNode(true);
                  //set new Ids to XForm elements
                  this.resetXFormIds(newControl);

                  repeatContentWrapper.appendChild(newControl);
               }
            } 
            else
            { 
               //add it normally
               final String elementName = getElementName(element, xForm);

               final String path = (pathToRoot.length() == 0
                                    ? elementName
                                    : pathToRoot + "/" + elementName);
 
               final Element newDefaultInstanceElement = xForm.createElement(elementName);
               if (element.getConstraintType() != XSConstants.VC_NONE)
               {
                  Node value = xForm.createTextNode(element.getConstraintValue());
                  newDefaultInstanceElement.appendChild(value);
               }

               this.addElement(xForm,
                               modelSection,
                               newDefaultInstanceElement,
                               repeatContentWrapper,
                               schema,
                               element,
                               element.getTypeDefinition(),
                               path);

               // update the default instance
               if (this.getOccurance(element).minimum == 0)
               {
                  defaultInstanceElement.appendChild(newDefaultInstanceElement.cloneNode(true));
               }
               else
               {
                  for (int i = 0; i < this.getOccurance(element).minimum; i++)
                  {
                     defaultInstanceElement.appendChild(newDefaultInstanceElement.cloneNode(true));
                  }
               }
            }
         } 
         else 
         { //XSWildcard -> ignore ?
            //LOGGER.warn("XSWildcard found in group from "+owner.getName()+" for pathToRoot="+pathToRoot);
         }
      }

      if (LOGGER.isDebugEnabled())
         LOGGER.debug("--- end of addGroup from owner=" + owner.getName());
   }

   /**
    * Add a repeat section if maxOccurs > 1.
    */
   private Element addRepeatIfNecessary(final Document xForm,
                                        final Element modelSection,
                                        final Element formSection,
                                        final XSTypeDefinition controlType,
                                        final Occurs o ,
                                        final String pathToRoot) 
   {

      // add xforms:repeat section if this element re-occurs
      //
      if (o.maximum == 1) 
         return formSection;

      if (LOGGER.isDebugEnabled())
         LOGGER.debug("DEBUG: AddRepeatIfNecessary for multiple element for type " + 
                      controlType.getName() + ", maxOccurs=" + o.maximum);
	
      final Element repeatSection = 
         xForm.createElementNS(SchemaFormBuilder.XFORMS_NS, SchemaFormBuilder.XFORMS_NS_PREFIX + "repeat");

      //bind instead of repeat
      //repeatSection.setAttributeNS(SchemaFormBuilder.XFORMS_NS,SchemaFormBuilder.XFORMS_NS_PREFIX + "nodeset",pathToRoot);
      // bind -> last element in the modelSection
      Element bind = DOMUtil.getLastChildElement(modelSection);
      String bindId = null;

      if (bind != null && 
          bind.getLocalName() != null && 
          "bind".equals(bind.getLocalName())) 
         bindId = bind.getAttributeNS(SchemaFormBuilder.XFORMS_NS, "id");
      else 
      {
         LOGGER.warn("addRepeatIfNecessary: bind not found: " + bind
                     + " (model selection name=" + modelSection.getNodeName() + ")");

         //if no bind is found -> modelSection is already a bind, get its parent last child
         bind = DOMUtil.getLastChildElement(modelSection.getParentNode());

         if (bind != null &&
             bind.getLocalName() != null && 
             "bind".equals(bind.getLocalName())) 
            bindId = bind.getAttributeNS(SchemaFormBuilder.XFORMS_NS, "id");
         else 
            LOGGER.warn("addRepeatIfNecessary: bind really not found");
      }

      repeatSection.setAttributeNS(SchemaFormBuilder.XFORMS_NS,
                                   SchemaFormBuilder.XFORMS_NS_PREFIX + "bind",
                                   bindId);
      this.setXFormsId(repeatSection);

      //appearance=full is more user friendly
      repeatSection.setAttributeNS(SchemaFormBuilder.XFORMS_NS,
                                   SchemaFormBuilder.XFORMS_NS_PREFIX + "appearance",
                                   "full");

      final Element controlWrapper =
         this.wrapper.createControlsWrapper(repeatSection);
      formSection.appendChild(controlWrapper);

      //add a group inside the repeat?
      //            Element group = xForm.createElementNS(SchemaFormBuilder.XFORMS_NS,
      //this.SchemaFormBuilder.XFORMS_NS_PREFIX + "group");
      //this.setXFormsId(group);
      //repeatSection.appendChild(group);
      //repeatSection = group;
      return repeatSection;
   }

   /**
    * if "createBind", a bind is created, otherwise bindId is used
    */
   private void addSimpleType(final Document xForm,
                              final Element modelSection,
                              Element formSection,
                              final XSModel schema,
                              final XSTypeDefinition controlType,
                              final String owningElementName,
                              final XSObject owner,
                              final String pathToRoot,
                              final Occurs o) 
   {

      if (LOGGER.isDebugEnabled())
         LOGGER.debug("addSimpleType for " + controlType.getName() + 
                      " (owningElementName=" + owningElementName + ")");

      if (owner != null)
         LOGGER.debug("owner is " + owner.getClass() +
                      ", name is " + owner.getName());
      // create the <xforms:bind> element and add it to the model.
      Element bindElement = xForm.createElementNS(SchemaFormBuilder.XFORMS_NS, 
                                                  SchemaFormBuilder.XFORMS_NS_PREFIX + "bind");
      String bindId = this.setXFormsId(bindElement);
      modelSection.appendChild(bindElement);
      bindElement = this.startBindElement(bindElement, schema, controlType, owner, pathToRoot, o);

      // add a group if a repeat !
      if (owner instanceof XSElementDeclaration && o.maximum != 1) 
      {
         final Element groupElement = this.createGroup(xForm, 
                                                       modelSection, 
                                                       formSection, 
                                                       (XSElementDeclaration)owner);
         //set content
         formSection = (groupElement == modelSection
                        ? groupElement
                        : this.wrapper.createGroupContentWrapper(groupElement));
      }

      //eventual repeat
      final Element repeatSection = this.addRepeatIfNecessary(xForm, 
                                                              modelSection, 
                                                              formSection, 
                                                              controlType, 
                                                              o, 
                                                              pathToRoot);
	
      // create the form control element
      //put a wrapper for the repeat content, but only if it is really a repeat
      Element contentWrapper = repeatSection;

      if (repeatSection != formSection) 
      {
         //content of repeat
         contentWrapper = this.wrapper.createGroupContentWrapper(repeatSection);

         //if there is a repeat -> create another bind with "."
//         Element bindElement2 =
//            xForm.createElementNS(SchemaFormBuilder.XFORMS_NS, 
//                                  SchemaFormBuilder.XFORMS_NS_PREFIX + "bind");
//         String bindId2 = this.setXFormsId(bindElement2);
//         bindElement2.setAttributeNS(SchemaFormBuilder.XFORMS_NS,
//                                     SchemaFormBuilder.XFORMS_NS_PREFIX + "nodeset",
//                                     ".");
//         bindElement.appendChild(bindElement2);
//
//         bindElement = bindElement2;
//         bindId = bindId2;
      }

      final String caption = owner != null ? createCaption(owner) : createCaption(owningElementName);
      final Element formControl = this.createFormControl(xForm,
                                                         schema,
                                                         caption,
                                                         controlType,
                                                         owner,
                                                         bindId,
                                                         bindElement,
                                                         o);
      contentWrapper.appendChild(this.wrapper.createControlsWrapper(formControl));

      // if this is a repeatable then set ref to point to current element
      // not sure if this is a workaround or this is just the way XForms works...
      //
      if (!repeatSection.equals(formSection)) 
         formControl.setAttributeNS(SchemaFormBuilder.XFORMS_NS,
                                    SchemaFormBuilder.XFORMS_NS_PREFIX + "ref",
                                    ".");

      Element hint = createHint(xForm, owner);
      if (hint != null)
         formControl.appendChild(hint);

      //add selector if repeat
      //if (repeatSection != formSection)
      //this.addSelector(xForm, (Element) formControl.getParentNode());
      //
      // TODO: Generate help message based on datatype and restrictions
      this.endFormControl(formControl, controlType, o);
      this.endBindElement(bindElement);
   }

   private void addSimpleType(final Document xForm,
                              final Element modelSection,
                              final Element formSection,
                              final XSModel schema,
                              final XSSimpleTypeDefinition controlType,
                              final XSElementDeclaration owner,
                              final String pathToRoot) 
   {
      this.addSimpleType(xForm,
                         modelSection,
                         formSection,
                         schema,
                         controlType,
                         owner.getName(),
                         owner,
                         pathToRoot,
                         this.getOccurance(owner));
   }

   private void addSimpleType(final Document xForm,
                              final Element modelSection,
                              final Element formSection,
                              final XSModel schema,
                              final XSSimpleTypeDefinition controlType,
                              final XSAttributeUse owningAttribute,
                              final String pathToRoot) 
   {
      this.addSimpleType(xForm,
                         modelSection,
                         formSection,
                         schema,
                         controlType,
                         owningAttribute.getAttrDeclaration().getName(),
                         owningAttribute,
                         pathToRoot,
                         new Occurs(owningAttribute.getRequired() ? 1 : 0, 1));
   }
				  

   private void buildTypeTree(final XSTypeDefinition type, 
                              final TreeSet descendents) 
   {
      if (type == null) 
         return;

      if (descendents.size() > 0) 
      {
         //TreeSet compatibleTypes = (TreeSet) typeTree.get(type.getName());
         TreeSet<XSTypeDefinition> compatibleTypes = this.typeTree.get(type.getName());
	    
         if (compatibleTypes == null) 
         {
            //compatibleTypes = new TreeSet(descendents);
            compatibleTypes = new TreeSet<XSTypeDefinition>(this.typeExtensionSorter);
            this.typeTree.put(type.getName(), compatibleTypes);
         }
         compatibleTypes.addAll(descendents);
      }

      final XSTypeDefinition parentType = type.getBaseType();
	
      if (parentType == null ||
          type.getTypeCategory() != parentType.getTypeCategory()) 
         return;
      if (type != parentType && 
          (parentType.getName() == null || !parentType.getName().equals("anyType"))) 
      {
	    
         //TreeSet newDescendents=new TreeSet(descendents);
         final TreeSet<XSTypeDefinition> newDescendents = 
            new TreeSet<XSTypeDefinition>(this.typeExtensionSorter);
         newDescendents.addAll(descendents);
         
         //extension (we only add it to "newDescendants" because we don't want
         //to have a type descendant to itself, but to consider it for the parent
         if (type.getTypeCategory() == XSTypeDefinition.COMPLEX_TYPE) 
         {
            final XSComplexTypeDefinition complexType = (XSComplexTypeDefinition)type;
            if (complexType.getDerivationMethod() == XSConstants.DERIVATION_EXTENSION && 
                !complexType.getAbstract() && 
                !descendents.contains(type)) 
               newDescendents.add(type);
         }
         //note: extensions are impossible on simpleTypes !
	    
         this.buildTypeTree(parentType, newDescendents);
      }
   }

   private void buildTypeTree(final XSModel schema) 
   {
      LOGGER.debug("buildTypeTree " + schema);
      // build the type tree for complex types
      final XSNamedMap types = schema.getComponents(XSConstants.TYPE_DEFINITION);
      for (int i = 0; i < types.getLength(); i++) 
      {
         final XSTypeDefinition t = (XSTypeDefinition)types.item(i);
         if (t.getTypeCategory() == XSTypeDefinition.COMPLEX_TYPE) 
         {
            final XSComplexTypeDefinition type = (XSComplexTypeDefinition)t;
            this.buildTypeTree(type, 
                               new TreeSet<XSTypeDefinition>(this.typeExtensionSorter));
         }
      }

      // build the type tree for simple types
      for (int i = 0; i < types.getLength(); i++) 
      {
         final XSTypeDefinition t = (XSTypeDefinition)types.item(i);
         if (t.getTypeCategory() == XSTypeDefinition.SIMPLE_TYPE) 
         {
            this.buildTypeTree((XSSimpleTypeDefinition)t, 
                               new TreeSet<XSTypeDefinition>(this.typeExtensionSorter));
         }
      }

      // print out type hierarchy for debugging purposes
      if (LOGGER.isDebugEnabled()) 
      {
         for (String typeName : this.typeTree.keySet())
         {
            TreeSet descendents = this.typeTree.get(typeName);
            LOGGER.debug(">>>> for " + typeName + " Descendants=\n ");
            Iterator it = descendents.iterator();
            while (it.hasNext()) 
            {
               XSTypeDefinition desc = (XSTypeDefinition) it.next();
               LOGGER.debug("      " + desc.getName());
            }
         }
      }
   }

   private Element createFormControl(final Document xForm,
                                     final XSModel schema,
                                     final String caption,
                                     final XSTypeDefinition controlType,
                                     final XSObject owner,
                                     final String bindId,
                                     final Element bindElement,
                                     final Occurs o) 
   {
      Element formControl = null;
      if (controlType.getTypeCategory() == XSTypeDefinition.SIMPLE_TYPE &&
          ((XSSimpleTypeDefinition)controlType).getItemType() != null) 
      {
            formControl = this.createControlForListType(xForm,
                                                        (XSSimpleTypeDefinition)controlType,
                                                        caption,
                                                        bindElement);
      }
      else if (controlType.getTypeCategory() == XSTypeDefinition.SIMPLE_TYPE &&
               ((XSSimpleTypeDefinition)controlType).isDefinedFacet(XSSimpleTypeDefinition.FACET_ENUMERATION))
      {
         formControl = this.createControlForEnumerationType(xForm,
                                                            (XSSimpleTypeDefinition)controlType,
                                                            caption,
                                                            bindElement);
      } 
      else if (controlType.getTypeCategory() == XSTypeDefinition.COMPLEX_TYPE && 
               "anyType".equals(controlType.getName())) 
      {
         formControl = this.createControlForAnyType(xForm, caption, controlType);
      }
      else
      {
         formControl = this.createControlForAtomicType(xForm,
                                                       caption,
                                                       (XSSimpleTypeDefinition)controlType);
      }

      this.startFormControl(formControl, controlType);
      formControl.setAttributeNS(SchemaFormBuilder.XFORMS_NS, 
                                 SchemaFormBuilder.XFORMS_NS_PREFIX + "bind", 
                                 bindId);

      // TODO: Enhance alert statement based on facet restrictions.
      // TODO: Enhance to support minOccurs > 1 and maxOccurs > 1.
      // TODO: Add i18n/l10n suppport to this - use java MessageFormatter...
      //
      //       e.g. Please provide a valid value for 'Address'. 'Address' is a mandatory decimal field.
      //
      final Element alertElement = xForm.createElementNS(SchemaFormBuilder.XFORMS_NS,
                                                         SchemaFormBuilder.XFORMS_NS_PREFIX + "alert");
      formControl.appendChild(alertElement);
      this.setXFormsId(alertElement);

      String alert = this.extractPropertyFromAnnotation(ALFRESCO_NS,
                                                        "alert",
                                                        this.getAnnotation(owner));
      if (alert == null)
         alert = ("Please provide a valid value for '" + caption + "'." +
                  " '" + caption + 
                  "' is " + (o.minimum == 0 ? "an optional" : "a required") + " '" + 
                  createCaption(this.getXFormsTypeName(xForm, schema, controlType)) + 
                  "' value.");
      alertElement.appendChild(xForm.createTextNode(alert));
      return formControl;
   }

   /**
    * used to get the type name that will be used in the XForms document
    *
    * @param context     the element which will serve as context for namespaces
    * @param controlType the type from which we want the name
    * @return the complete type name (with namespace prefix) of the type in the XForms doc
    */
   protected String getXFormsTypeName(final Document xformsDocument,
                                      final XSModel schema,
                                      final XSTypeDefinition controlType) 
   {
      final Element context = xformsDocument.getDocumentElement();
      final String typeName = controlType.getName();
      final String typeNS = controlType.getNamespace();

      //if we use XMLSchema types:
      //first check if it is a simple type named in the XMLSchema
      if (controlType.getTypeCategory() != XSTypeDefinition.SIMPLE_TYPE ||
          typeName == null || 
          typeName.length() == 0 ||
          schema.getTypeDefinition(typeName, typeNS) == null)
      {
         //use built in type
         return SchemaFormBuilder.DATA_TYPE_TO_NAME.get(this.getBuiltInType(controlType));
      }

      //type is globally defined
      //use schema type

      //local type name
      String localTypeName = typeName;
      int index = typeName.indexOf(":");
      if (index > -1 && typeName.length() > index)
         localTypeName = typeName.substring(index + 1);
	
      //namespace prefix in this document
      String prefix = NamespaceCtx.getPrefix(context, typeNS);
	
      //completeTypeName = new prefix + local name
      String result = localTypeName;
      if (prefix != null && prefix.length() != 0)
         result = prefix + ":" + localTypeName;
	
      if (LOGGER.isDebugEnabled())
         LOGGER.debug("getXFormsTypeName: typeName=" + typeName + 
                      ", typeNS=" + typeNS + 
                      ", result=" + result);
      return result;
   }

   private Document createFormTemplate(final String formId)
   {
      final FormsService ts = FormsService.getInstance();
      final Document xForm = ts.newDocument();

      final Element envelopeElement = this.wrapper.createEnvelope(xForm);

      final Map<String, String> namespaces = new HashMap<String, String>();
      namespaces.put(SchemaFormBuilder.CHIBA_NS_PREFIX, SchemaFormBuilder.CHIBA_NS);
      namespaces.put(SchemaFormBuilder.XFORMS_NS_PREFIX, SchemaFormBuilder.XFORMS_NS);
      namespaces.put(SchemaFormBuilder.XLINK_NS_PREFIX, SchemaFormBuilder.XLINK_NS);
      namespaces.put(SchemaFormBuilder.XMLEVENTS_NS_PREFIX, SchemaFormBuilder.XMLEVENTS_NS);
      namespaces.put(SchemaFormBuilder.XMLSCHEMA_INSTANCE_NS_PREFIX, SchemaFormBuilder.XMLSCHEMA_INSTANCE_NS);
      namespaces.put(SchemaFormBuilder.ALFRESCO_NS_PREFIX, SchemaFormBuilder.ALFRESCO_NS);
      for (String nsPrefix : namespaces.keySet())
      {
         this.addNamespace(envelopeElement, nsPrefix, namespaces.get(nsPrefix));
      }

      //base
      if (this.base != null && this.base.length() != 0)
         envelopeElement.setAttributeNS(XML_NAMESPACE_URI, "xml:base", this.base);

      //model element
      Element modelElement = xForm.createElementNS(SchemaFormBuilder.XFORMS_NS, 
                                                   SchemaFormBuilder.XFORMS_NS_PREFIX + "model");
      this.setXFormsId(modelElement);
      Element modelWrapper = this.wrapper.createModelWrapper(modelElement);
      envelopeElement.appendChild(modelWrapper);

      //form control wrapper -> created by wrapper
      //Element formWrapper = xForm.createElement("body");
      //envelopeElement.appendChild(formWrapper);
      Element formWrapper = this.wrapper.createFormWrapper(envelopeElement);

      return xForm;
   }

   private Element createGroup(Document xForm,
                               Element modelSection,
                               Element formSection,
                               XSElementDeclaration owner) {
      // add a group node and recurse
      Element groupElement =
         xForm.createElementNS(SchemaFormBuilder.XFORMS_NS, 
                               SchemaFormBuilder.XFORMS_NS_PREFIX + "group");
      groupElement = startFormGroup(groupElement, owner);

      if (groupElement == null)
         groupElement = modelSection;
      else
      {
         this.setXFormsId(groupElement);

         Element controlsWrapper = this.wrapper.createControlsWrapper(groupElement);

         //groupElement = (Element) formSection.appendChild(groupElement);
         formSection.appendChild(controlsWrapper);

         Element captionElement =
            xForm.createElementNS(SchemaFormBuilder.XFORMS_NS,
                                  SchemaFormBuilder.XFORMS_NS_PREFIX + "label");
         groupElement.appendChild(captionElement);
         this.setXFormsId(captionElement);
         captionElement.appendChild(xForm.createTextNode(createCaption(owner)));
      }
      return groupElement;
   }

   public String createCaption(final String text,
                               final XSObject o)
   {
      return this.createCaption(text, this.getAnnotation(o));
   }

   public String createCaption(final String text, 
                               final XSAnnotation annotation)
   {
      final String s = this.extractPropertyFromAnnotation(ALFRESCO_NS, "label", annotation);
      return s != null ? s : this.createCaption(text);
   }

   /**
    * Creates a caption for the provided text extracted from the XML Schema.
    * The implementation is responsible for reformatting the provided string to make it
    * suitable to be displayed to users of the XForm. This typically includes translating
    * XML tag name style identifiers (e.g. customerStreetAddress) into more reader friendly
    * captions (e.g. Customer Street Address).
    *
    * @param text The string value to be reformatted for use as a caption.
    * @return The caption.
    */
   public String createCaption(String text) 
   {
      // if the word is all upper case, then set to lower case and continue
      if (text.equals(text.toUpperCase()))
         text = text.toLowerCase();
      final String[] s = text.split("[-_\\ ]");
      final StringBuffer result = new StringBuffer();
      for (int i = 0; i < s.length; i++)
      {
         if (i != 0)
            result.append(' ');
         if (s[i].length() > 1)
            result.append(Character.toUpperCase(s[i].charAt(0)) +  
                          s[i].substring(1, s[i].length()));
         else
            result.append(s[i]);
      }
      return result.toString();
   }

   /**
    * Creates a caption for the provided XML Schema attribute.
    * The implementation is responsible for providing an appropriate caption
    * suitable to be displayed to users of the XForm. This typically includes translating
    * XML tag name style identifiers (e.g. customerStreetAddress) into more reader friendly
    * captions (e.g. Customer Street Address).
    *
    * @param attribute The XML schema attribute for which a caption is required.
    * @return The caption.
    */
   public String createCaption(XSAttributeDeclaration attribute) 
   {
      // TODO: Improve i18n/l10n of caption - may have to use
      //       a custom <appinfo> element in the XML Schema to do this.
      //
      return createCaption(attribute.getName(), attribute);
   }

   public String createCaption(XSAttributeUse attribute) 
   {
      // TODO: Improve i18n/l10n of caption - may have to use
      //       a custom <appinfo> element in the XML Schema to do this.
      //
      return createCaption(attribute.getAttrDeclaration().getName(), attribute);
   }

   /**
    * Creates a caption for the provided XML Schema element.
    * The implementation is responsible for providing an appropriate caption
    * suitable to be displayed to users of the XForm. This typically includes translating
    * XML tag name style identifiers (e.g. customerStreetAddress) into more reader friendly
    * captions (e.g. Customer Street Address).
    *
    * @param element The XML schema element for which a caption is required.
    * @return The caption.
    */
   public String createCaption(XSElementDeclaration element) 
   {
      // TODO: Improve i18n/l10n of caption - may have to use
      //       a custom <appinfo> element in the XML Schema to do this.
      //
      return createCaption(element.getName(), element);
   }

   /**
    * __UNDOCUMENTED__
    *
    * @param element __UNDOCUMENTED__
    * @return __UNDOCUMENTED__
    */
   public String createCaption(XSObject element) 
   {
      // TODO: Improve i18n/l10n of caption - may have to use
      //       a custom <appinfo> element in the XML Schema to do this.
      //
      if (element instanceof XSElementDeclaration)
         return createCaption((XSElementDeclaration)element);
      if (element instanceof XSAttributeDeclaration)
         return createCaption((XSAttributeDeclaration)element);
      if (element instanceof XSAttributeUse)
         return createCaption((XSAttributeUse)element);
      
      LOGGER.warn("WARNING: createCaption: element is not an attribute nor an element: "
                  + element.getClass().getName());
      return null;
   }

   /**
    * Creates a form control for an XML Schema any type.
    * <br/>
    * This method is called when the form builder determines a form control is required for
    * an any type.
    * The implementation of this method is responsible for creating an XML element of the
    * appropriate type to receive a value for <b>controlType</b>. The caller is responsible
    * for adding the returned element to the form and setting caption, bind, and other
    * standard elements and attributes.
    *
    * @param xForm       The XForm document.
    * @param controlType The XML Schema type for which the form control is to be created.
    * @return The element for the form control.
    */
   public Element createControlForAnyType(Document xForm,
                                          String caption,
                                          XSTypeDefinition controlType) 
   {
      Element control = xForm.createElementNS(SchemaFormBuilder.XFORMS_NS, 
                                              SchemaFormBuilder.XFORMS_NS_PREFIX + "textarea");
      this.setXFormsId(control);
      control.setAttributeNS(SchemaFormBuilder.CHIBA_NS, 
                             SchemaFormBuilder.CHIBA_NS_PREFIX + "height", 
                             "3");

      //label
      Element captionElement = xForm.createElementNS(SchemaFormBuilder.XFORMS_NS,
                                                     SchemaFormBuilder.XFORMS_NS_PREFIX + "label");
      control.appendChild(captionElement);
      this.setXFormsId(captionElement);
      captionElement.appendChild(xForm.createTextNode(caption));

      return control;
   }

   /**
    * Creates a form control for an XML Schema simple atomic type.
    * <p/>
    * This method is called when the form builder determines a form control is required for
    * an atomic type.
    * The implementation of this method is responsible for creating an XML element of the
    * appropriate type to receive a value for <b>controlType</b>. The caller is responsible
    * for adding the returned element to the form and setting caption, bind, and other
    * standard elements and attributes.
    *
    * @param xForm       The XForm document.
    * @param controlType The XML Schema type for which the form control is to be created.
    * @return The element for the form control.
    */
   public Element createControlForAtomicType(Document xForm,
                                             String caption,
                                             XSSimpleTypeDefinition controlType) 
   {
      Element control;

      if ("boolean".equals(controlType.getName()))
      {
         control = xForm.createElementNS(SchemaFormBuilder.XFORMS_NS,
                                         SchemaFormBuilder.XFORMS_NS_PREFIX + "select1");
         this.setXFormsId(control);
         final String[] values = { "true", "false" };
         for (String v : values)
         {
            final Element item = this.createXFormsItem(xForm, v, v);
            control.appendChild(item);
         }
      } 
      else if ("anyURI".equals(controlType.getName()))
      {
         control = xForm.createElementNS(SchemaFormBuilder.XFORMS_NS,
                                         SchemaFormBuilder.XFORMS_NS_PREFIX + "upload");
         final Element e = xForm.createElementNS(SchemaFormBuilder.XFORMS_NS,
                                                 SchemaFormBuilder.XFORMS_NS_PREFIX + "filename");
         control.appendChild(e);
         e.setAttributeNS(SchemaFormBuilder.XFORMS_NS,
                          SchemaFormBuilder.XFORMS_NS_PREFIX + "ref",
                          ".");
         this.setXFormsId(control);
      }
      else 
      {
         control = xForm.createElementNS(SchemaFormBuilder.XFORMS_NS, SchemaFormBuilder.XFORMS_NS_PREFIX + "input");
         this.setXFormsId(control);
      }

      //label
      final Element captionElement = 
         xForm.createElementNS(SchemaFormBuilder.XFORMS_NS,
                               SchemaFormBuilder.XFORMS_NS_PREFIX + "label");
      control.appendChild(captionElement);
      this.setXFormsId(captionElement);
      captionElement.appendChild(xForm.createTextNode(caption));

      return control;
   }

   /**
    * Creates a form control for an XML Schema simple type restricted by an enumeration.
    * This method is called when the form builder determines a form control is required for
    * an enumerated type.
    * The implementation of this method is responsible for creating an XML element of the
    * appropriate type to receive a value for <b>controlType</b>. The caller is responsible
    * for adding the returned element to the form and setting caption, bind, and other
    * standard elements and attributes.
    *
    * @param xForm       The XForm document.
    * @param controlType The XML Schema type for which the form control is to be created.
    * @param caption     The caption for the form control. The caller The purpose of providing the caption
    *                    is to permit the implementation to add a <b>[Select1 .... ]</b> message that involves the caption.
    * @param bindElement The bind element for this control. The purpose of providing the bind element
    *                    is to permit the implementation to add a isValid attribute to the bind element that prevents
    *                    the <b>[Select1 .... ]</b> item from being selected.
    * @return The element for the form control.
    */
   public Element createControlForEnumerationType(Document xForm,
                                                  XSSimpleTypeDefinition controlType,
                                                  String caption,
                                                  Element bindElement) 
   {
      // TODO: Figure out an intelligent or user determined way to decide between
      // selectUI style (listbox, menu, combobox, radio) (radio and listbox best apply)
      // Possibly look for special appInfo section in the schema and if not present default to comboBox...
      //
      // For now, use radio if enumValues < DEFAULT_LONG_LIST_MAX_SIZE otherwise use combobox
      //
      final StringList enumFacets = controlType.getLexicalEnumeration();
      if (enumFacets.getLength() <= 0)
         return null;

      Element control = xForm.createElementNS(SchemaFormBuilder.XFORMS_NS,
                                              SchemaFormBuilder.XFORMS_NS_PREFIX + "select1");
      this.setXFormsId(control);

      //label
      Element captionElement1 = xForm.createElementNS(SchemaFormBuilder.XFORMS_NS,
                                                      SchemaFormBuilder.XFORMS_NS_PREFIX + "label");
      control.appendChild(captionElement1);
      this.setXFormsId(captionElement1);
      captionElement1.appendChild(xForm.createTextNode(caption));

      Element choices = xForm.createElementNS(SchemaFormBuilder.XFORMS_NS,
                                              SchemaFormBuilder.XFORMS_NS_PREFIX + "choices");
      this.setXFormsId(choices);

      final XSObjectList mvFacets = controlType.getMultiValueFacets();
      if (mvFacets.getLength() != 1)
         throw new RuntimeException("expected exactly one MultiValueFacet for " + controlType);

      final XSObjectList annotations = 
         ((XSMultiValueFacet)mvFacets.item(0)).getAnnotations();

      final Map<String, XSAnnotation> enumValues = 
         new HashMap<String, XSAnnotation>(enumFacets.getLength());
      for (int i = 0; i < enumFacets.getLength(); i++) 
      {
         enumValues.put(enumFacets.item(i), 
                        (annotations.getLength() == enumFacets.getLength()
                         ? (XSAnnotation)annotations.item(i)
                         : null));
      }

      control.setAttributeNS(SchemaFormBuilder.XFORMS_NS,
                             SchemaFormBuilder.XFORMS_NS_PREFIX + "appearance",
                             (enumFacets.getLength() < Long.parseLong(getProperty(SELECTONE_LONG_LIST_SIZE_PROP))
                              ? getProperty(SELECTONE_UI_CONTROL_SHORT_PROP)
                              : getProperty(SELECTONE_UI_CONTROL_LONG_PROP)));

      if (enumFacets.getLength() >= Long.parseLong(getProperty(SELECTONE_LONG_LIST_SIZE_PROP))) 
      {
         // add the "Please select..." instruction item for the combobox
         // and set the isValid attribute on the bind element to check for the "Please select..."
         // item to indicate that is not a valid value
         //
         final String pleaseSelect = "[Select1 " + caption + "]";
         final Element item = this.createXFormsItem(xForm, pleaseSelect, pleaseSelect);
         choices.appendChild(item);

         // not(purchaseOrder/state = '[Choose State]')
         //String isValidExpr = "not(" + bindElement.getAttributeNS(SchemaFormBuilder.XFORMS_NS,"nodeset") + " = '" + pleaseSelect + "')";
         // ->no, not(. = '[Choose State]')
         final String isValidExpr = "not( . = '" + pleaseSelect + "')";

         //check if there was a constraint
         String constraint = bindElement.getAttributeNS(SchemaFormBuilder.XFORMS_NS, "constraint");

         constraint = (constraint != null && constraint.length() != 0
                       ? constraint + " and " + isValidExpr
                       : isValidExpr);

         bindElement.setAttributeNS(SchemaFormBuilder.XFORMS_NS,
                                    SchemaFormBuilder.XFORMS_NS_PREFIX + "constraint",
                                    constraint);
      }

      control.appendChild(choices);

      this.addChoicesForSelectControl(xForm, choices, enumValues);

      return control;
   }

   /**
    * Creates a form control for an XML Schema simple list type.
    * <p/>
    * This method is called when the form builder determines a form control is required for
    * a list type.
    * The implementation of this method is responsible for creating an XML element of the
    * appropriate type to receive a value for <b>controlType</b>. The caller is responsible
    * for adding the returned element to the form and setting caption, bind, and other
    * standard elements and attributes.
    *
    * @param xForm       The XForm document.
    * @param listType    The XML Schema list type for which the form control is to be created.
    * @param caption     The caption for the form control. The caller The purpose of providing the caption
    *                    is to permit the implementation to add a <b>[Select1 .... ]</b> message that involves the caption.
    * @param bindElement The bind element for this control. The purpose of providing the bind element
    *                    is to permit the implementation to add a isValid attribute to the bind element that prevents
    *                    the <b>[Select1 .... ]</b> item from being selected.
    * @return The element for the form control.
    */
   public Element createControlForListType(final Document xForm,
                                           final XSSimpleTypeDefinition listType,
                                           final String caption,
                                           final Element bindElement) 
   {
      XSSimpleTypeDefinition controlType = listType.getItemType();

      final StringList enumFacets = controlType.getLexicalEnumeration();
      if (enumFacets.getLength() <= 0) 
         return null;
      Element control = xForm.createElementNS(SchemaFormBuilder.XFORMS_NS,
                                              SchemaFormBuilder.XFORMS_NS_PREFIX + "select");
      this.setXFormsId(control);

      //label
      Element captionElement = xForm.createElementNS(SchemaFormBuilder.XFORMS_NS,
                                                     SchemaFormBuilder.XFORMS_NS_PREFIX + "label");
      control.appendChild(captionElement);
      this.setXFormsId(captionElement);
      captionElement.appendChild(xForm.createTextNode(caption));

      final XSObjectList mvFacets = controlType.getMultiValueFacets();
      if (mvFacets.getLength() != 1)
         throw new RuntimeException("expected exactly one MultiValueFacet for " + controlType);

      final XSObjectList annotations = 
         ((XSMultiValueFacet)mvFacets.item(0)).getAnnotations();

      final Map<String, XSAnnotation> enumValues = 
         new HashMap<String, XSAnnotation>(enumFacets.getLength());
      for (int i = 0; i < enumFacets.getLength(); i++) 
      {
         enumValues.put(enumFacets.item(i), 
                        (annotations.getLength() == enumFacets.getLength()
                         ? (XSAnnotation)annotations.item(i)
                         : null));
      }

      // TODO: Figure out an intelligent or user determined way to decide between
      // selectUI style (listbox, menu, combobox, radio) (radio and listbox best apply)
      // Possibly look for special appInfo section in the schema and if not present default to checkBox...
      //
      // For now, use checkbox if there are < DEFAULT_LONG_LIST_MAX_SIZE items, otherwise use long control
      //
      control.setAttributeNS(SchemaFormBuilder.XFORMS_NS,
                             SchemaFormBuilder.XFORMS_NS_PREFIX + "appearance",
                             (enumValues.size() < Long.parseLong(getProperty(SELECTMANY_LONG_LIST_SIZE_PROP))
                              ? getProperty(SELECTMANY_UI_CONTROL_SHORT_PROP)
                              : getProperty(SELECTMANY_UI_CONTROL_LONG_PROP)));
      Element choices = xForm.createElementNS(SchemaFormBuilder.XFORMS_NS,
                                              SchemaFormBuilder.XFORMS_NS_PREFIX + "choices");
      this.setXFormsId(choices);
      control.appendChild(choices);

      this.addChoicesForSelectControl(xForm, choices, enumValues);

      return control;
   }

   /**
    * Creates a hint XML Schema annotated node (AttributeDecl or ElementDecl).
    * The implementation is responsible for providing an xforms:hint element for the
    * specified schemaNode suitable to be dsipalayed to users of the XForm. The caller
    * is responsible for adding the returned element to the form.
    * This typically includes extracting documentation from the element/attribute's
    * annotation/documentation elements and/or extracting the same information from the
    * element/attribute's type annotation/documentation.
    *
    * @param schemaNode The string value to be reformatted for use as a caption.
    * @return The xforms:hint element. If a null value is returned a hint is not added.
    */
   public Element createHint(Document xForm, XSObject node) 
   {
      final XSAnnotation annotation = this.getAnnotation(node);
      if (annotation == null)
         return null;
      final String s = this.extractPropertyFromAnnotation(ALFRESCO_NS, "hint", annotation);
      if (s == null)
         return null;
      final Element hintElement = 
         xForm.createElementNS(SchemaFormBuilder.XFORMS_NS, 
                               SchemaFormBuilder.XFORMS_NS_PREFIX + "hint");
      this.setXFormsId(hintElement);
      hintElement.appendChild(xForm.createTextNode(s));
      return hintElement;
   }

   private XSAnnotation getAnnotation(final XSObject o)
   {
      return (o instanceof XSElementDeclaration
              ? ((XSElementDeclaration)o).getAnnotation()
              :  (o instanceof XSAttributeDeclaration
                  ? ((XSAttributeDeclaration)o).getAnnotation()
                  : (o instanceof XSAttributeUse
                     ? ((XSAttributeUse)o).getAttrDeclaration().getAnnotation()
                     : null)));
   }

   /**
    * This method is invoked after the form builder is finished creating and processing
    * a bind element. Implementations may choose to use this method to add/inspect/modify
    * the bindElement prior to the builder moving onto the next bind element.
    *
    * @param bindElement The bind element being processed.
    */
   public void endBindElement(Element bindElement) 
   {
   }

   /**
    * This method is invoked after the form builder is finished creating and processing
    * a form control. Implementations may choose to use this method to add/inspect/modify
    * the controlElement prior to the builder moving onto the next control.
    *
    * @param controlElement The form control element that was created.
    * @param controlType    The XML Schema type for which <b>controlElement</b> was created.
    */
   public void endFormControl(Element controlElement,
                              XSTypeDefinition controlType,
                              Occurs occurs)
   {
   }

   /**
    * This method is invoked after an xforms:bind element is created for the specified SimpleType.
    * The implementation is responsible for setting setting any/all bind attributes
    * except for <b>id</b> and <b>ref</b> - these have been automatically set
    * by the caller (and should not be touched by implementation of startBindElement)
    * prior to invoking startBindElement.
    * The caller automatically adds the returned element to the model section of
    * the form.
    *
    * @param bindElement The bindElement being processed.
    * @param controlType XML Schema type of the element/attribute this bind is for.
    * @param minOccurs   The minimum number of occurences for this element/attribute.
    * @param maxOccurs   The maximum number of occurences for this element/attribute.
    * @return The bind Element to use in the XForm - bindElement or a replacement.
    */
   public Element startBindElement(final Element bindElement,
                                   final XSModel schema,
                                   final XSTypeDefinition controlType,
                                   final XSObject owner,
                                   final String pathToRoot,
                                   final Occurs o)
   {
      // START WORKAROUND
      // Due to a Chiba bug, anyType is not a recognized type name.
      // so, if this is an anyType, then we'll just skip the type
      // setting.
      //
      // type.getName() may be 'null' for anonymous types, so compare against
      // static string (see bug #1172541 on sf.net)

      String nodeset = pathToRoot;
      if (o.isRepeated() && o.minimum == 0)
          nodeset = pathToRoot + "[position() != last()]";

      bindElement.setAttributeNS(SchemaFormBuilder.XFORMS_NS,
                                 SchemaFormBuilder.XFORMS_NS_PREFIX + "nodeset",
                                 nodeset);

      if (!"anyType".equals(controlType.getName())) 
      {
         String typeName = this.getXFormsTypeName(bindElement.getOwnerDocument(),
                                                  schema, 
                                                  controlType);
         if (typeName != null && typeName.length() != 0)
            bindElement.setAttributeNS(SchemaFormBuilder.XFORMS_NS,
                                       SchemaFormBuilder.XFORMS_NS_PREFIX + "type",
                                       typeName);
      }

      final short constraintType = 
         (owner instanceof XSElementDeclaration 
          ? ((XSElementDeclaration)owner).getConstraintType()
          : (owner instanceof XSAttributeDeclaration
             ? ((XSAttributeDeclaration)owner).getConstraintType()
             : (owner instanceof XSAttributeUse
                ? ((XSAttributeUse)owner).getConstraintType()
                : XSConstants.VC_NONE)));
              
      bindElement.setAttributeNS(SchemaFormBuilder.XFORMS_NS,
                                 SchemaFormBuilder.XFORMS_NS_PREFIX + "readonly",
                                 (constraintType == XSConstants.VC_FIXED) + "()");

      bindElement.setAttributeNS(SchemaFormBuilder.XFORMS_NS,
                                 SchemaFormBuilder.XFORMS_NS_PREFIX + "required",
                                 (o.minimum != 0) + "()");
	
      //no more minOccurs & maxOccurs element: add a constraint if maxOccurs>1:
      //count(.) <= maxOccurs && count(.) >= minOccurs
      String minConstraint = null;
      String maxConstraint = null;

      if (o.minimum > 1) 
      {
         //if 0 or 1 -> no constraint (managed by "required")
         minConstraint = "count(.) >= " + o.minimum;
         bindElement.setAttributeNS(ALFRESCO_NS,
                                    ALFRESCO_NS_PREFIX + "minimum",
                                    String.valueOf(o.minimum));
      } 

      if (o.maximum > 1) 
      {
         //if 1 or unbounded -> no constraint
         maxConstraint = "count(.) <= " + o.maximum;
         bindElement.setAttributeNS(ALFRESCO_NS,
                                    ALFRESCO_NS_PREFIX + "maximum",
                                    String.valueOf(o.maximum));

      }

      final String constraint = (minConstraint != null && maxConstraint != null
                                 ? minConstraint + " and " + maxConstraint
                                 : (minConstraint != null
                                    ? minConstraint
                                    : maxConstraint));
      if (constraint != null)
         bindElement.setAttributeNS(SchemaFormBuilder.XFORMS_NS,
                                    SchemaFormBuilder.XFORMS_NS_PREFIX + "constraint",
                                    constraint);
      return bindElement;
   }

   /**
    * This method is invoked after the form builder creates a form control
    * via a createControlForXXX() method but prior to decorating the form control
    * with common attributes such as a caption, hint, help text elements,
    * bind attributes, etc.
    * The returned element is used in the XForm in place of controlElement.
    * Implementations may choose to use this method to substitute controlElement
    * with a different element, or perform any other processing on controlElement
    * prior to it being added to the form.
    *
    * @param controlElement The form control element that was created.
    * @param controlType    The XML Schema type for which <b>controlElement</b> was created.
    * @return The Element to use in the XForm - controlElement or a replacement.
    */
   public Element startFormControl(Element controlElement,
                                   XSTypeDefinition controlType) 
   {
      return controlElement;
   }

   /**
    * This method is invoked after an xforms:group element is created for the specified
    * ElementDecl. A group is created whenever an element is encountered in the XML Schema
    * that contains other elements and attributes (complex types or mixed content types).
    * The caller automatically adds the returned element to the XForm.
    *
    * @param groupElement  The groupElement being processed.
    * @param schemaElement The schemaElement for the group.
    * @return The group Element to use in the XForm - groupElement or a replacement. If a null
    *         value is returned, the group is not created.
    */
   public Element startFormGroup(Element groupElement,
                                 XSElementDeclaration schemaElement) 
   {
      return groupElement;
   }

   /**
    * Get a fully qualified name for this element, and eventually declares a new prefix for the namespace if
    * it was not declared before
    *
    * @param element
    * @param xForm
    * @return
    */
   private String getElementName(final XSElementDeclaration element, 
                                 final Document xForm) 
   {
      String elementName = element.getName();
      String namespace = element.getNamespace();
      if (namespace != null && namespace.length() != 0) 
      {
         String prefix;
         if ((prefix = (String) namespacePrefixes.get(namespace)) == null) 
         {
            String basePrefix = (namespace.substring(namespace.lastIndexOf('/', namespace.length()-2)+1));
            int i=1;
            prefix = basePrefix;
            while (namespacePrefixes.containsValue(prefix)) 
            {
               prefix = basePrefix + (i++);
            }
            namespacePrefixes.put(namespace, prefix);
            xForm.getDocumentElement().setAttributeNS(XMLNS_NAMESPACE_URI, 
                                                      "xmlns:" + prefix, 
                                                      namespace);
         }
         elementName = prefix + ":" + elementName;
      }
      return elementName;
   }

   //XXXarielb factor out to a utility method...
   public static XSModel loadSchema(final Document schemaDocument)
      throws FormBuilderException
   {
      try
      {
         // Get DOM Implementation using DOM Registry
         System.setProperty(DOMImplementationRegistry.PROPERTY,
                            "org.apache.xerces.dom.DOMXSImplementationSourceImpl");

         final DOMImplementationRegistry registry =
            DOMImplementationRegistry.newInstance();

         final DOMImplementationLS lsImpl = (DOMImplementationLS)
            registry.getDOMImplementation("XML 1.0 LS 3.0");
         final FormsService ts = FormsService.getInstance();
         final LSInput in = lsImpl.createLSInput();
         in.setStringData(ts.writeXMLToString(schemaDocument));

         final XSImplementation xsImpl = (XSImplementation)
            registry.getDOMImplementation("XS-Loader");
         final XSLoader schemaLoader = xsImpl.createXSLoader(null);
         final XSModel result = schemaLoader.load(in);
         if (result == null)
         {
            throw new FormBuilderException("invalid schema");
         }
         return result;
      } 
      catch (ClassNotFoundException x) 
      {
         throw new FormBuilderException(x);
      } 
      catch (InstantiationException x) 
      {
         throw new FormBuilderException(x);
      }
      catch (IllegalAccessException x) 
      {
         throw new FormBuilderException(x);
      }
   }

   private void addNamespace(final Element e,
                             final String nsPrefix,
                             final String ns)
   {
      final String p = nsPrefix.substring(0, nsPrefix.length() - 1);
      if (!e.hasAttributeNS(XMLNS_NAMESPACE_URI, p))
         e.setAttributeNS(XMLNS_NAMESPACE_URI, "xmlns:" + p, ns);
   }

   private void createTriggersForRepeats(final Document xformsDocument)
   {
      LOGGER.debug("creating triggers for repeats");
      final HashMap<String, Element> bindIdToBind = new HashMap<String, Element>();
      final NodeList binds = xformsDocument.getElementsByTagNameNS(SchemaFormBuilder.XFORMS_NS, "bind");
      for (int i = 0; i < binds.getLength(); i++)
      {
         final Element b = (Element)binds.item(i);
         LOGGER.debug("adding bind " + b.getAttributeNS(SchemaFormBuilder.XFORMS_NS, "id"));
         bindIdToBind.put(b.getAttributeNS(SchemaFormBuilder.XFORMS_NS, "id"), b);
      }

      final NodeList repeats = xformsDocument.getElementsByTagNameNS(SchemaFormBuilder.XFORMS_NS, "repeat");
      final HashMap<Element, Element> bindToRepeat = new HashMap<Element, Element>(); 
      for (int i = 0; i < repeats.getLength(); i++)
      {
         Element r = (Element)repeats.item(i);
         LOGGER.debug("processing repeat " + r.getAttributeNS(SchemaFormBuilder.XFORMS_NS, "id"));
         Element bind = bindIdToBind.get(r.getAttributeNS(SchemaFormBuilder.XFORMS_NS, "bind"));
         bindToRepeat.put(bind, r);

         String xpath = "";
         
         do
         {
            if (xpath.length() != 0)
            {
               xpath = '/' + xpath;
            }

            LOGGER.debug("walking bind " + bind.getAttributeNS(SchemaFormBuilder.XFORMS_NS, "id"));
            String s = bind.getAttributeNS(SchemaFormBuilder.XFORMS_NS, "nodeset");
            s = s.replaceAll("^([^\\[]+).*$", "$1");
            if (bindToRepeat.containsKey(bind) && !r.equals(bindToRepeat.get(bind)))
            {
               s += "[index(\'" + bindToRepeat.get(bind).getAttributeNS(SchemaFormBuilder.XFORMS_NS, "id") + "\')]";
            }
            xpath = s + xpath;
            bind = ((SchemaFormBuilder.XFORMS_NS_PREFIX + "bind").equals(bind.getParentNode().getNodeName())
                    ? (Element)bind.getParentNode()
                    : null);
         }
         while (bind != null);
         this.createTriggersForRepeat(xformsDocument,
                                      r.getAttributeNS(SchemaFormBuilder.XFORMS_NS, "id"),
                                      xpath,
                                      r.getAttributeNS(SchemaFormBuilder.XFORMS_NS, "bind"));
      }
   }

   private Element createTriggerForRepeat(final Document xForm,
                                          final String id,
                                          final String bindId,
                                          final String label,
                                          final Element action)
   {
      final Element trigger =
         xForm.createElementNS(SchemaFormBuilder.XFORMS_NS,
                               SchemaFormBuilder.XFORMS_NS_PREFIX + "trigger");
      this.setXFormsId(trigger, id != null ? id : null);
 
      //copy the bind attribute
      trigger.setAttributeNS(SchemaFormBuilder.XFORMS_NS,
                             SchemaFormBuilder.XFORMS_NS_PREFIX + "bind",
                             bindId);
      //label insert
      final Element triggerLabel =
         xForm.createElementNS(SchemaFormBuilder.XFORMS_NS,
                               SchemaFormBuilder.XFORMS_NS_PREFIX + "label");
      this.setXFormsId(triggerLabel);
      trigger.appendChild(triggerLabel);

      triggerLabel.appendChild(xForm.createTextNode(label));

      //insert action
      final Element actionWrapper =
         xForm.createElementNS(SchemaFormBuilder.XFORMS_NS,
                               SchemaFormBuilder.XFORMS_NS_PREFIX + "action");
      actionWrapper.appendChild(action);
      trigger.appendChild(action);
      this.setXFormsId(action);

      return trigger;
   }
				  

   /**
    * add triggers to use the repeat elements (allow to add an element, ...)
    */
   private void createTriggersForRepeat(final Document xformsDocument,
                                        final String repeatId,
                                        final String nodeset,
                                        final String bindId)
   {
      //xforms:at = xforms:index from the "id" attribute on the repeat element
      ///////////// insert //////////////////
      //trigger insert

      Element action =
         xformsDocument.createElementNS(SchemaFormBuilder.XFORMS_NS,
                                        SchemaFormBuilder.XFORMS_NS_PREFIX + "insert");
      action.setAttributeNS(SchemaFormBuilder.XFORMS_NS,
                            SchemaFormBuilder.XFORMS_NS_PREFIX + "nodeset",
                            nodeset);
      action.setAttributeNS(SchemaFormBuilder.XFORMS_NS,
                            SchemaFormBuilder.XFORMS_NS_PREFIX + "position",
                            "before");
      action.setAttributeNS(SchemaFormBuilder.XFORMS_NS,
                            SchemaFormBuilder.XFORMS_NS_PREFIX + "at",
                            "1");

      final Element trigger_insert_before =
         this.createTriggerForRepeat(xformsDocument, 
                                     repeatId + "-insert_before",
                                     bindId,
                                     "insert at beginning", 
                                     action);

      action = xformsDocument.createElementNS(SchemaFormBuilder.XFORMS_NS,
                                     SchemaFormBuilder.XFORMS_NS_PREFIX + "insert");
      action.setAttributeNS(SchemaFormBuilder.XFORMS_NS,
                            SchemaFormBuilder.XFORMS_NS_PREFIX + "nodeset",
                            nodeset);
      action.setAttributeNS(SchemaFormBuilder.XFORMS_NS,
                            SchemaFormBuilder.XFORMS_NS_PREFIX + "position",
                            "after");
      action.setAttributeNS(SchemaFormBuilder.XFORMS_NS,
                            SchemaFormBuilder.XFORMS_NS_PREFIX + "at",
                            SchemaFormBuilder.XFORMS_NS_PREFIX + "index('" + repeatId + "')");
					
      final Element trigger_insert_after =
         this.createTriggerForRepeat(xformsDocument, 
                                     repeatId + "-insert_after",
                                     bindId,
                                     "insert after selected", 
                                     action);

      //trigger delete
      action = xformsDocument.createElementNS(SchemaFormBuilder.XFORMS_NS,
                                              SchemaFormBuilder.XFORMS_NS_PREFIX + "delete");
      action.setAttributeNS(SchemaFormBuilder.XFORMS_NS,
                            SchemaFormBuilder.XFORMS_NS_PREFIX + "nodeset",
                            nodeset);
      action.setAttributeNS(SchemaFormBuilder.XFORMS_NS,
                            SchemaFormBuilder.XFORMS_NS_PREFIX + "at",
                            SchemaFormBuilder.XFORMS_NS_PREFIX + "index('" + repeatId + "')");

      final Element trigger_delete =
         this.createTriggerForRepeat(xformsDocument, 
                                     repeatId != null ? repeatId + "-delete" : null,
                                     bindId,
                                     "delete selected", 
                                     action);

      final Element formSection = (Element)xformsDocument.getDocumentElement().getLastChild();
      //add the triggers
      final Element wrapper_triggers =
         this.wrapper.createControlsWrapper(trigger_insert_before);

      if (wrapper_triggers == trigger_insert_before) 
      {
         //no wrapper
         formSection.appendChild(trigger_insert_before);
         formSection.appendChild(trigger_insert_after);
         formSection.appendChild(trigger_delete);
      } 
      else 
      {
         formSection.appendChild(wrapper_triggers);
         final Element insert_parent = (Element)trigger_insert_before.getParentNode();

         if (insert_parent != null)
         {
            insert_parent.appendChild(trigger_insert_after);
            insert_parent.appendChild(trigger_delete);
         }
      }
   }

   private Element createXFormsItem(final Document xformsDocument,
                                    final String label,
                                    final String value)
   {
      final Element item = xformsDocument.createElementNS(SchemaFormBuilder.XFORMS_NS, 
                                                          SchemaFormBuilder.XFORMS_NS_PREFIX + "item");
      this.setXFormsId(item);
      Element e = xformsDocument.createElementNS(SchemaFormBuilder.XFORMS_NS, 
                                                 SchemaFormBuilder.XFORMS_NS_PREFIX + "label");
      this.setXFormsId(e);
      e.appendChild(xformsDocument.createTextNode(label));
      item.appendChild(e);
		
      e = xformsDocument.createElementNS(SchemaFormBuilder.XFORMS_NS, 
                                         SchemaFormBuilder.XFORMS_NS_PREFIX + "value");
      this.setXFormsId(e);
      e.appendChild(xformsDocument.createTextNode(value));
      item.appendChild(e);
      return item;
   }
}