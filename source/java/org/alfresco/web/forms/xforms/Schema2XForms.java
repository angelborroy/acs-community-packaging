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
import javax.xml.transform.*;
import org.alfresco.service.namespace.NamespaceService;
import org.alfresco.web.forms.XMLUtil;
import org.apache.commons.jxpath.JXPathContext;
import org.apache.commons.jxpath.Pointer;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.xerces.xs.*;
import org.chiba.xml.dom.DOMUtil;
import org.chiba.xml.ns.NamespaceConstants;
import org.chiba.xml.ns.NamespaceResolver;
import org.w3c.dom.*;
import org.w3c.dom.ls.*;

/**
 * An abstract implementation of the Schema2XForms interface allowing
 * an XForm to be automatically generated for an XML Schema definition.
 * This abstract class implements the buildForm and buildFormAsString methods
 * and associated helper but relies on concrete subclasses to implement other
 * required interface methods (createXXX, startXXX, and endXXX methods).
 *
 * @author $Author: unl $
 */
public class Schema2XForms
{

   /////////////////////////////////////////////////////////////////////////////

   public enum SubmitMethod
   {
      POST("post"),
      GET("get"),
      PUT("put"),
      FORM_DATA_POST("form-data-post"),
      URL_ENCODED_POST("urlencoded-post");

      private final String value;

      SubmitMethod(final String value)
      {
         this.value = value;
      }

      public String toString()
      {
         return this.value;
      }
   }

   /////////////////////////////////////////////////////////////////////////////

   private final static Log LOGGER = LogFactory.getLog(Schema2XForms.class);

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
   private final SubmitMethod submitMethod;
   private final String base;

   /**
    * generic counter -> replaced by an hashMap with:
    * keys: name of the elements
    * values: "Long" representing the counter for this element
    */
   private final Map<String, Long> counter = new HashMap<String, Long>();
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
   private TreeMap<String, TreeSet<XSTypeDefinition>> typeTree;

   /**
    * Creates a new Schema2XForms object.
    *
    * @param action         _UNDOCUMENTED_
    * @param submitMethod   _UNDOCUMENTED_
    */
   public Schema2XForms(final String action,
                        final SubmitMethod submitMethod,
                        final String base)
   {
      reset();

      this.action = action;
      this.submitMethod = submitMethod;
      this.base = base;
   }

   /**
    * Get the current set of properties used by implementations of Schema2XForms.
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
   public Document buildXForm(final Document instanceDocument,
                              final Document schemaDocument,
                              String rootElementName,
                              final ResourceBundle resourceBundle)
      throws FormBuilderException
   {
      final XSModel schema = SchemaUtil.parseSchema(schemaDocument);
      this.typeTree = SchemaUtil.buildTypeTree(schema);

      //refCounter = 0;
      this.counter.clear();

      final Document xformsDocument = this.createFormTemplate(rootElementName);

      //find form element: last element created
      final Element formSection = (Element)
         xformsDocument.getDocumentElement().getLastChild();
      final Element modelSection = (Element)
         xformsDocument.getDocumentElement().getElementsByTagNameNS(NamespaceConstants.XFORMS_NS,
                                                                    "model").item(0);

      //add XMLSchema if we use schema types
      modelSection.setAttributeNS(NamespaceConstants.XFORMS_NS, "schema", "#schema-1");
      final Element importedSchemaDocumentElement = (Element)
         xformsDocument.importNode(schemaDocument.getDocumentElement(), true);
      importedSchemaDocumentElement.setAttributeNS(null, "id", "schema-1");

      modelSection.appendChild(importedSchemaDocumentElement);

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
//	    envelopeElement.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI,
//					   "xmlns:schema",
//					   this.targetNamespace);


      final XSElementDeclaration rootElementDecl =
         schema.getElementDeclaration(rootElementName, this.targetNamespace);
      if (rootElementDecl == null)
      {
         throw new FormBuilderException("Invalid root element tag name ["
                                        + rootElementName
                                        + ", targetNamespace="
                                        + this.targetNamespace
                                        + "]");
      }

      rootElementName = this.getElementName(rootElementDecl, xformsDocument);
      final Element instanceElement =
         xformsDocument.createElementNS(NamespaceConstants.XFORMS_NS,
                                        NamespaceConstants.XFORMS_PREFIX + ":instance");
      modelSection.appendChild(instanceElement);
      this.setXFormsId(instanceElement);

      final Element defaultInstanceDocumentElement = xformsDocument.createElement(rootElementName);
      this.addNamespace(defaultInstanceDocumentElement,
                        NamespaceConstants.XMLSCHEMA_INSTANCE_PREFIX,
                        NamespaceConstants.XMLSCHEMA_INSTANCE_NS);
      if (this.targetNamespace != null)
      {
         this.addNamespace(defaultInstanceDocumentElement,
                           schemaDocument.lookupPrefix(this.targetNamespace),
                           this.targetNamespace);
         this.addNamespace(xformsDocument.getDocumentElement(),
                           schemaDocument.lookupPrefix(this.targetNamespace),
                           this.targetNamespace);
      }

      Element importedInstanceDocumentElement = null;
      if (instanceDocument == null || instanceDocument.getDocumentElement() == null)
      {
         instanceElement.appendChild(defaultInstanceDocumentElement);
      }
      else
      {
         Element instanceDocumentElement = instanceDocument.getDocumentElement();
         if (!instanceDocumentElement.getNodeName().equals(rootElementName))
         {
            throw new IllegalArgumentException("instance document root tag name invalid.  " +
                                               "expected " + rootElementName +
                                               ", got " + instanceDocumentElement.getNodeName());
         }
         LOGGER.debug("importing rootElement from other document");
         importedInstanceDocumentElement = (Element)
            xformsDocument.importNode(instanceDocumentElement, true);
         //add XMLSchema instance NS
         this.addNamespace(importedInstanceDocumentElement,
                           NamespaceConstants.XMLSCHEMA_INSTANCE_PREFIX,
                           NamespaceConstants.XMLSCHEMA_INSTANCE_NS);
         instanceElement.appendChild(importedInstanceDocumentElement);

         final Element prototypeInstanceElement =
            xformsDocument.createElementNS(NamespaceConstants.XFORMS_NS,
                                           NamespaceConstants.XFORMS_PREFIX + ":instance");
         modelSection.appendChild(prototypeInstanceElement);
         this.setXFormsId(prototypeInstanceElement, "instance_prototype");
         prototypeInstanceElement.appendChild(defaultInstanceDocumentElement);
      }

      final Element rootGroup = this.addElement(xformsDocument,
                                                modelSection,
                                                defaultInstanceDocumentElement,
                                                formSection,
                                                schema,
                                                rootElementDecl,
                                                "/" + getElementName(rootElementDecl, xformsDocument),
                                                resourceBundle);
      if (rootGroup.getNodeName() != NamespaceConstants.XFORMS_PREFIX + ":group")
      {
         throw new FormBuilderException("Expected root form element to be a group.  Generated a " + 
                                        rootGroup.getNodeName() + " instead");
      }
      this.setXFormsId(rootGroup, "alfresco-xforms-root-group");

      if (importedInstanceDocumentElement != null)
      {
         this.insertPrototypeNodes(importedInstanceDocumentElement,
                                   defaultInstanceDocumentElement);
      }

      this.createSubmitElements(xformsDocument, modelSection, rootGroup);
      this.createTriggersForRepeats(xformsDocument, rootGroup);

      final Comment comment =
         xformsDocument.createComment("This XForm was generated by " + this.getClass().getName() +
                                      " on " + (new Date()) + " from the '" + rootElementName +
                                      "' element of the '" + this.targetNamespace + "' XML Schema.");
      xformsDocument.getDocumentElement().insertBefore(comment,
                                                       xformsDocument.getDocumentElement().getFirstChild());
      return xformsDocument;
   }

   /**
    * Reset the Schema2XForms to default values.
    */
   public void reset()
   {
      this.counter.clear();
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

   private void insertPrototypeNodes(final Element instanceDocumentElement,
                                     final Element prototypeDocumentElement)
   {
      final JXPathContext prototypeContext =
         JXPathContext.newContext(prototypeDocumentElement);
      prototypeContext.registerNamespace(NamespaceService.ALFRESCO_PREFIX,
                                         NamespaceService.ALFRESCO_URI);
      final JXPathContext instanceContext =
         JXPathContext.newContext(instanceDocumentElement);
      instanceContext.registerNamespace(NamespaceService.ALFRESCO_PREFIX,
                                        NamespaceService.ALFRESCO_URI);

      class PrototypeInsertionData
      {
         final Node prototype;
         final List nodes;
         final boolean append;

         PrototypeInsertionData(final Node prototype,
                                final List nodes,
                                final boolean append)
         {
            this.prototype = prototype;
            this.nodes = nodes;
            this.append = append;
         }
      };
      final List<PrototypeInsertionData> prototypesToInsert =
         new LinkedList<PrototypeInsertionData>();

      final Iterator it =
         prototypeContext.iteratePointers("//*[@" + NamespaceService.ALFRESCO_PREFIX +
                                          ":prototype='true']");
      while (it.hasNext())
      {
         final Pointer p = (Pointer)it.next();
         String path = p.asPath().replaceAll("\\[\\d+\\]", "") + "[last()]";
         LOGGER.debug("evaluating " + path + " against instance document");
         List l = instanceContext.selectNodes(path);
         if (l.size() != 0)
         {
            prototypesToInsert.add(new PrototypeInsertionData((Node)p.getNode(),
                                                              l,
                                                              false));
         }
         else
         {
            int index = path.lastIndexOf('/');
            path = index == 0 ? "/" : path.substring(0, index);
            l = instanceContext.selectNodes(path);
            prototypesToInsert.add(new PrototypeInsertionData((Node)p.getNode(),
                                                              l,
                                                              true));
         }
      }
      for (PrototypeInsertionData data : prototypesToInsert)
      {
         LOGGER.debug("adding prototype for " + data.prototype.getNodeName() +
                      " to " + data.nodes.size() + " nodes");
         for (Object o : data.nodes)
         {
            final Node n = (Node)o;
            if (data.append)
            {
               n.appendChild(data.prototype.cloneNode(true));
            }
            else if (n.getNextSibling() != null)
            {
               n.getParentNode().insertBefore(data.prototype.cloneNode(true),
                                              n.getNextSibling());
            }
            else
            {
               n.getParentNode().appendChild(data.prototype.cloneNode(true));
            }
         }
      }
   }

   public void removePrototypeNodes(final Element instanceDocumentElement)
   {
      final Map<String, LinkedList<Element>> prototypes =
         new HashMap<String, LinkedList<Element>>();
      final NodeList children = instanceDocumentElement.getChildNodes();
      for (int i = 0; i < children.getLength(); i++)
      {
         if (! (children.item(i) instanceof Element))
         {
            continue;
         }
         final String nodeName = children.item(i).getNodeName();
         if (! prototypes.containsKey(nodeName))
         {
            prototypes.put(nodeName, new LinkedList<Element>());
         }
         prototypes.get(nodeName).add((Element)children.item(i));
      }

      for (LinkedList<Element> l : prototypes.values())
      {
         for (Element e : l)
         {
            if (e.hasAttributeNS(NamespaceService.ALFRESCO_URI, "prototype"))
            {
               assert "true".equals(e.getAttributeNS(NamespaceService.ALFRESCO_URI,
                                                     "prototype"));
               e.removeAttributeNS(NamespaceService.ALFRESCO_URI, "prototype");

               if (l.getLast().equals(e))
               {
                  e.getParentNode().removeChild(e);
               }
            }
            if (e.getParentNode() != null)
            {
               this.removePrototypeNodes(e);
            }
         }
      }
   }

   protected String setXFormsId(final Element el)
   {
      return this.setXFormsId(el, null);
   }

   protected String setXFormsId(final Element el, String id)
   {
      if (el.hasAttributeNS(null, "id"))
      {
         el.removeAttributeNS(null, "id");
      }
      if (id == null)
      {
         final String name = el.getLocalName();
         final Long l = this.counter.get(name);
         final long count = (l != null) ? l : 0;

         // increment the counter
         this.counter.put(name, new Long(count + 1));

         id = name + "_" + count;
      }
      el.setAttributeNS(null, "id", id);
      return id;
   }

   /**
    * method to set an Id to this element and to all XForms descendants of this element
    */
   private void resetXFormIds(final Element newControl)
   {
      if (newControl.getNamespaceURI() != null &&
          newControl.getNamespaceURI().equals(NamespaceConstants.XFORMS_NS))
      {
         this.setXFormsId(newControl);
      }

      //recursive call
      final NodeList children = newControl.getChildNodes();
      for (int i = 0; i < children.getLength(); i++)
      {
         final Node child = children.item(i);
         if (child.getNodeType() == Node.ELEMENT_NODE)
         {
            this.resetXFormIds((Element) child);
         }
      }
   }

   /**
    * _UNDOCUMENTED_
    *
    * @param xForm          _UNDOCUMENTED_
    * @param choicesElement _UNDOCUMENTED_
    * @param choiceValues   _UNDOCUMENTED_
    */
   protected void addChoicesForSelectControl(final Document xForm,
                                             final Element choicesElement,
                                             final Map<String, XSAnnotation> choiceValues,
                                             final ResourceBundle resourceBundle)
   {
      for (Map.Entry<String, XSAnnotation> choice : choiceValues.entrySet())
      {
         final Element item = this.createXFormsItem(xForm,
                                                    this.createCaption(choice.getKey(),
                                                                       choice.getValue(),
                                                                       resourceBundle),
                                                    choice.getKey());
         choicesElement.appendChild(item);
      }
   }

   /**
    */
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

         if (LOGGER.isDebugEnabled())
         {
            LOGGER.debug("addChoicesForSelectSwitchControl, processing " + textValue);
         }
         final Element item = this.createXFormsItem(xForm,
                                                    this.createCaption(textValue),
                                                    textValue);
         choicesElement.appendChild(item);

         /// action in the case
         final Element action = xForm.createElementNS(NamespaceConstants.XFORMS_NS,
                                                      NamespaceConstants.XFORMS_PREFIX + ":action");
         this.setXFormsId(action);
         item.appendChild(action);

         action.setAttributeNS(NamespaceConstants.XMLEVENTS_NS,
                               NamespaceConstants.XMLEVENTS_PREFIX + ":event",
                               "xforms-select");

         final Element toggle = xForm.createElementNS(NamespaceConstants.XFORMS_NS,
                                                      NamespaceConstants.XFORMS_PREFIX + ":toggle");
         this.setXFormsId(toggle);

         //build the case element
         final Element caseElement = xForm.createElementNS(NamespaceConstants.XFORMS_NS,
                                                           NamespaceConstants.XFORMS_PREFIX + ":case");
         final String caseId = this.setXFormsId(caseElement);
         result.put(textValue, caseElement);

         toggle.setAttributeNS(NamespaceConstants.XFORMS_NS,
                               NamespaceConstants.XFORMS_PREFIX + ":case",
                               caseId);

         //toggle.setAttributeNS(NamespaceConstants.XFORMS_NS,
         //NamespaceConstants.XFORMS_PREFIX + ":case",bindIdPrefix + "_" + textValue +"_case");
         action.appendChild(toggle);
      }
      return result;
   }

   private static String extractPropertyFromAnnotation(final String namespace,
                                                       final String elementName,
                                                       final XSAnnotation annotation,
                                                       final ResourceBundle resourceBundle)
   {
      if (annotation == null)
         return null;
      // write annotation to empty doc
      final Document doc = XMLUtil.newDocument();
      annotation.writeAnnotation(doc, XSAnnotation.W3C_DOM_DOCUMENT);

      final NodeList d = doc.getElementsByTagNameNS(namespace, elementName);
      if (d.getLength() == 0)
         return null;
      if (d.getLength() > 1)
         LOGGER.warn("expect exactly one value for " + namespace +
                     ":" + elementName +
                     ". found " + d.getLength());
      String result = DOMUtil.getTextNodeAsString(d.item(0));
      LOGGER.debug(namespace + ":" + elementName + " = " + result);
      if (result.startsWith("${") && result.endsWith("}") && resourceBundle != null)
      {
         result = result.substring("${".length(), result.length() - "}".length());
         LOGGER.debug("looking up key " + result + " in bundle " + resourceBundle);
         try
         {
            result = resourceBundle.getString(result);
         }
         catch (MissingResourceException mse)
         {
            LOGGER.debug("unable to find key " + result, mse);
            result = "$$" + result + "$$";
         }
      }
      return result;
   }

   private Element addAnyType(final Document xForm,
                              final Element modelSection,
                              final Element formSection,
                              final XSModel schema,
                              final XSTypeDefinition controlType,
                              final XSElementDeclaration owner,
                              final String pathToRoot,
                              final ResourceBundle resourceBundle)
   {
      return this.addSimpleType(xForm,
                                modelSection,
                                formSection,
                                schema,
                                controlType,
                                owner.getName(),
                                owner,
                                pathToRoot,
                                SchemaUtil.getOccurance(owner),
                                resourceBundle);
   }

   private void addAttributeSet(final Document xForm,
                                final Element modelSection,
                                final Element defaultInstanceElement,
                                final Element formSection,
                                final XSModel schema,
                                final XSComplexTypeDefinition controlType,
                                final XSElementDeclaration owner,
                                final String pathToRoot,
                                final boolean checkIfExtension,
                                final ResourceBundle resourceBundle)
      throws FormBuilderException
   {
      XSObjectList attrUses = controlType.getAttributeUses();

      if (attrUses == null)
      {
         return;
      }
      for (int i = 0; i < attrUses.getLength(); i++)
      {
         final XSAttributeUse currentAttributeUse = (XSAttributeUse)attrUses.item(i);
         final XSAttributeDeclaration currentAttribute =
            currentAttributeUse.getAttrDeclaration();

         String attributeName = currentAttributeUse.getName();
         if (attributeName == null || attributeName.length() == 0)
         {
            attributeName = currentAttributeUse.getAttrDeclaration().getName();
         }

         //test if extended !
         if (checkIfExtension &&
             SchemaUtil.doesAttributeComeFromExtension(currentAttributeUse, controlType))
         {
            if (LOGGER.isDebugEnabled())
            {
               LOGGER.debug("This attribute comes from an extension: recopy form controls. \n Model section: ");
               LOGGER.debug(XMLUtil.toString(modelSection));
            }

            //find the existing bind Id
            //(modelSection is the enclosing bind of the element)
            final NodeList binds = modelSection.getElementsByTagNameNS(NamespaceConstants.XFORMS_NS, "bind");
            String bindId = null;
            for (int j = 0; j < binds.getLength() && bindId == null; j++)
            {
               Element bind = (Element) binds.item(j);
               String nodeset = bind.getAttributeNS(NamespaceConstants.XFORMS_NS, "nodeset");
               if (nodeset != null)
               {
                  //remove "@" in nodeset
                  String name = nodeset.substring(1);
                  if (name.equals(attributeName))
                     bindId = bind.getAttributeNS(null, "id");
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
                  context.getPointer("//*[@" + NamespaceConstants.XFORMS_PREFIX + ":bind='" + bindId + "']");
               if (pointer != null)
               {
                  control = (Element)pointer.getNode();
               }
            }

            //copy it
            if (control == null)
            {
               LOGGER.warn("Corresponding control not found");
            }
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
            final String newPathToRoot =
               (pathToRoot == null || pathToRoot.length() == 0
                ? "@" + currentAttribute.getName()
                : (pathToRoot.endsWith("/")
                   ? pathToRoot + "@" + currentAttribute.getName()
                   : pathToRoot + "/@" + currentAttribute.getName()));

            LOGGER.debug("adding attribute " + attributeName +
                         " at " + newPathToRoot);
            try
            {
               final String defaultValue = (currentAttributeUse.getConstraintType() == XSConstants.VC_NONE
                                            ? null
                                            : currentAttributeUse.getConstraintValue());
               defaultInstanceElement.setAttributeNS(this.targetNamespace,
                                                     // XXXarielb - i probably need the prefix here i.e. "alf:" + attributeName
                                                     attributeName,
                                                     defaultValue);
            }
            catch (Exception e)
            {
               throw new FormBuilderException("error retrieving default value for attribute " +
                                              attributeName + " at " + newPathToRoot, e);
            }
            this.addSimpleType(xForm,
                               modelSection,
                               formSection,
                               schema,
                               currentAttribute.getTypeDefinition(),
                               currentAttributeUse,
                               newPathToRoot,
                               resourceBundle);
         }
      }
   }

   private Element addComplexType(final Document xForm,
                                  final Element modelSection,
                                  final Element defaultInstanceElement,
                                  final Element formSection,
                                  final XSModel schema,
                                  final XSComplexTypeDefinition controlType,
                                  final XSElementDeclaration owner,
                                  final String pathToRoot,
                                  boolean relative,
                                  final boolean checkIfExtension,
                                  final ResourceBundle resourceBundle)
      throws FormBuilderException
   {
      if (controlType == null)
      {
         if (LOGGER.isDebugEnabled())
         {
            LOGGER.debug("addComplexType: control type is null for pathToRoot="
                         + pathToRoot);
         }
         return null;
      }

      if (LOGGER.isDebugEnabled()) 
      {
         LOGGER.debug("addComplexType for " + controlType.getName() +
                      " owner " + (owner != null ? owner.getName() : "<no owner>"));
      }

      // add a group node and recurse
      final Element groupElement = this.createGroup(xForm,
                                                    modelSection,
                                                    formSection,
                                                    owner,
                                                    resourceBundle);
      final SchemaUtil.Occurance o = SchemaUtil.getOccurance(owner);
      final Element repeatSection = this.addRepeatIfNecessary(xForm,
                                                              modelSection,
                                                              groupElement,
                                                              controlType,
                                                              o,
                                                              pathToRoot);
      if (repeatSection != groupElement)
      {
         groupElement.setAttributeNS(NamespaceConstants.XFORMS_NS,
                                     NamespaceConstants.XFORMS_PREFIX + ":appearance",
                                     "repeated");

         // we have a repeat
         relative = true;
      }

      this.addComplexTypeChildren(xForm,
                                  modelSection,
                                  defaultInstanceElement,
                                  repeatSection,
                                  schema,
                                  controlType,
                                  owner,
                                  pathToRoot,
                                  relative,
                                  checkIfExtension,
                                  resourceBundle);
      return groupElement;
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
                                       final boolean checkIfExtension,
                                       final ResourceBundle resourceBundle)
      throws FormBuilderException
   {

      if (controlType == null)
      {
         return;
      }

      if (LOGGER.isDebugEnabled())
      {
         LOGGER.debug("addComplexTypeChildren for " + controlType.getName() +
                      " owner = " + (owner == null ? "null" : owner.getName()));
      }

      if (controlType.getContentType() == XSComplexTypeDefinition.CONTENTTYPE_MIXED ||
          (controlType.getContentType() == XSComplexTypeDefinition.CONTENTTYPE_SIMPLE &&
           controlType.getAttributeUses() != null &&
           controlType.getAttributeUses().getLength() > 0))
      {
         XSTypeDefinition base = controlType.getBaseType();
         if (LOGGER.isDebugEnabled())
         {
            LOGGER.debug("	Control type is mixed . base type=" + base.getName());
         }

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
                                  pathToRoot,
                                  resourceBundle);
            }
            else
            {
               LOGGER.warn("addComplexTypeChildren for mixed type with basic type complex !");
            }
         }
      }
      else if (LOGGER.isDebugEnabled())
      {
         LOGGER.debug("	Content type = " + controlType.getContentType());
      }


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
                           checkIfExtension,
                           resourceBundle);

      //process group
      final XSParticle particle = controlType.getParticle();
      if (particle != null)
      {
         final XSTerm term = particle.getTerm();
         if (LOGGER.isDebugEnabled())
         {
            LOGGER.debug("Particle of " + controlType.getName() +
                         " is " + (term instanceof XSModelGroup 
                                   ? "a group" 
                                   : "not a group") +
                         ": " + term.getClass().getName());
         }

         if (term instanceof XSModelGroup)
         {
            //call addGroup on this group
            this.addGroup(xForm,
                          modelSection,
                          defaultInstanceElement,
                          formSection,
                          schema,
                          (XSModelGroup)term,
                          controlType,
                          owner,
                          pathToRoot,
                          new SchemaUtil.Occurance(particle),
                          checkIfExtension,
                          resourceBundle);
         }
      }

      if (LOGGER.isDebugEnabled())
      {
         LOGGER.debug("--->end of addComplexTypeChildren for " + controlType.getName());
      }
   }

   /**
    * add an element to the XForms document: the bind + the control
    * (only the control if "withBind" is false)
    */
   private Element addElement(final Document xformsDocument,
                              final Element modelSection,
                              final Element defaultInstanceElement,
                              final Element formSection,
                              final XSModel schema,
                              final XSElementDeclaration elementDecl,
                              final String pathToRoot,
                              final ResourceBundle resourceBundle)
      throws FormBuilderException
   {
      XSTypeDefinition controlType = elementDecl.getTypeDefinition();
      if (controlType == null)
      {
         // TODO!!! Figure out why this happens... for now just warn...
         // seems to happen when there is an element of type IDREFS
         LOGGER.warn("WARNING!!! controlType is null for " + elementDecl +
                     ", " + elementDecl.getName());
         return null;
      }

      if (controlType.getTypeCategory() == XSTypeDefinition.SIMPLE_TYPE)
      {
         return this.addSimpleType(xformsDocument,
                            modelSection,
                            formSection,
                            schema,
                            (XSSimpleTypeDefinition) controlType,
                            elementDecl,
                            pathToRoot,
                            resourceBundle);
      }

      if (controlType.getTypeCategory() == XSTypeDefinition.COMPLEX_TYPE &&
          "anyType".equals(controlType.getName()))
      {
         return this.addAnyType(xformsDocument,
                                modelSection,
                                formSection,
                                schema,
                                (XSComplexTypeDefinition) controlType,
                                elementDecl,
                                pathToRoot,
                                resourceBundle);
      }

      if (controlType.getTypeCategory() != XSTypeDefinition.COMPLEX_TYPE)
      {
         throw new FormBuilderException("Unsupported type [" + elementDecl.getType() +
                                        "] for node [" + controlType.getName() + "]");
      }

      boolean relative = true;
      String typeName = controlType.getName();
      final TreeSet<XSTypeDefinition> compatibleTypes =
         typeName != null ? this.typeTree.get(controlType.getName()) : null;
      if (compatibleTypes == null && typeName != null && LOGGER.isDebugEnabled())
      {
         LOGGER.debug("No compatible type found for " + typeName);
      }
      if (typeName != null && compatibleTypes != null)
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
         final boolean typeIsAbstract = ((XSComplexTypeDefinition)controlType).getAbstract();
         if (!typeIsAbstract && compatibleTypes.size() == 0)
         {
            relative = true;
         }
         else if (typeIsAbstract && compatibleTypes.size() == 1)
         {
            // only one compatible type, set the controlType value
            // and fall through
            controlType = compatibleTypes.first();
            relative = true;
         }
         else if (typeIsAbstract && compatibleTypes.size() == 0)
         {
            //name not null but no compatibleType?
            relative = false;
         }
         else
         {
            return this.addElementWithMultipleCompatibleTypes(xformsDocument,
                                                              modelSection,
                                                              defaultInstanceElement,
                                                              formSection,
                                                              schema,
                                                              elementDecl,
                                                              compatibleTypes,
                                                              pathToRoot,
                                                              resourceBundle);
         }
      }

      if (!relative)
      {
         if (LOGGER.isDebugEnabled())
         {
            LOGGER.debug("addElement: bind is not relative for "
                         + elementDecl.getName());
         }
      }
      else
      {
         //create the bind in case it is a repeat
         if (LOGGER.isDebugEnabled())
         {
            LOGGER.debug(">>>Adding empty bind for " + typeName);
         }

         // create the <xforms:bind> element and add it to the model.
         final Element bindElement =
            xformsDocument.createElementNS(NamespaceConstants.XFORMS_NS,
                                  NamespaceConstants.XFORMS_PREFIX + ":bind");
         final String bindId = this.setXFormsId(bindElement);
         bindElement.setAttributeNS(NamespaceConstants.XFORMS_NS,
                                    NamespaceConstants.XFORMS_PREFIX + ":nodeset",
                                    pathToRoot);

         modelSection.appendChild(bindElement);
         LOGGER.debug("adding bind for control " + controlType +
                      " type " + typeName +
                      " path to root " + pathToRoot);
         this.startBindElement(bindElement,
                               schema,
                               controlType,
                               null,
                               pathToRoot,
                               SchemaUtil.getOccurance(elementDecl));
      }
      return this.addComplexType(xformsDocument,
                                 modelSection,
                                 defaultInstanceElement,
                                 formSection,
                                 schema,
                                 (XSComplexTypeDefinition)controlType,
                                 elementDecl,
                                 pathToRoot,
                                 true,
                                 false,
                                 resourceBundle);

   }

   private Element addElementWithMultipleCompatibleTypes(final Document xformsDocument,
                                                         final Element modelSection,
                                                         final Element defaultInstanceElement,
                                                         final Element formSection,
                                                         final XSModel schema,
                                                         final XSElementDeclaration elementDecl,
                                                         final TreeSet<XSTypeDefinition> compatibleTypes,
                                                         final String pathToRoot,
                                                         final ResourceBundle resourceBundle)
      throws FormBuilderException
   {

      LOGGER.debug("&&&&&&&&&&&&&&& path to root =  " + pathToRoot);
      // look for compatible types
      final XSTypeDefinition controlType = elementDecl.getTypeDefinition();

         //get possible values
      final List<XSTypeDefinition> enumValues = new LinkedList<XSTypeDefinition>();
      //add the type (if not abstract)
      if (!((XSComplexTypeDefinition) controlType).getAbstract())
      {
         enumValues.add(controlType);
      }

      //add compatible types
      enumValues.addAll(compatibleTypes);

      final Element control =
         xformsDocument.createElementNS(NamespaceConstants.XFORMS_NS,
                                        NamespaceConstants.XFORMS_PREFIX + ":select1");
      final String select1Id = this.setXFormsId(control);

      final Element choices =
         xformsDocument.createElementNS(NamespaceConstants.XFORMS_NS,
                                        NamespaceConstants.XFORMS_PREFIX + ":choices");
      this.setXFormsId(choices, select1Id + "-choices");
      final String caption = this.createCaption(elementDecl.getName() + " Type");
      control.appendChild(this.createLabel(xformsDocument, caption));

      // multiple compatible types for this element exist
      // in the schema - allow the user to choose from
      // between compatible non-abstract types
      Element bindElement = xformsDocument.createElementNS(NamespaceConstants.XFORMS_NS,
                                                           NamespaceConstants.XFORMS_PREFIX + ":bind");
      String bindId = this.setXFormsId(bindElement);

      bindElement.setAttributeNS(NamespaceConstants.XFORMS_NS,
                                 NamespaceConstants.XFORMS_PREFIX + ":nodeset",
                                 pathToRoot + "/@xsi:type");

      modelSection.appendChild(bindElement);
      control.setAttributeNS(NamespaceConstants.XFORMS_NS,
                             NamespaceConstants.XFORMS_PREFIX + ":bind",
                             bindId);

      //add the "element" bind, in addition
      Element bindElement2 = xformsDocument.createElementNS(NamespaceConstants.XFORMS_NS,
                                                            NamespaceConstants.XFORMS_PREFIX + ":bind");
      String bindId2 = this.setXFormsId(bindElement2);
      bindElement2.setAttributeNS(NamespaceConstants.XFORMS_NS,
                                  NamespaceConstants.XFORMS_PREFIX + ":nodeset",
                                  pathToRoot);

      modelSection.appendChild(bindElement2);

      control.setAttributeNS(NamespaceConstants.XFORMS_NS,
                             NamespaceConstants.XFORMS_PREFIX + ":appearance",
                             (enumValues.size() < Long.parseLong(getProperty(SELECTONE_LONG_LIST_SIZE_PROP))
                              ? getProperty(SELECTONE_UI_CONTROL_SHORT_PROP)
                              : getProperty(SELECTONE_UI_CONTROL_LONG_PROP)));

      if (enumValues.size() >= Long.parseLong(getProperty(SELECTONE_LONG_LIST_SIZE_PROP)))
      {
         // add the "Please select..." instruction item for the combobox
         // and set the isValid attribute on the bind element to check for the "Please select..."
         // item to indicate that is not a valid value
         //
         final String pleaseSelect = "[Select1 " + caption + "]";
         final Element item = this.createXFormsItem(xformsDocument, pleaseSelect, pleaseSelect);
         choices.appendChild(item);

         // not(purchaseOrder/state = '[Choose State]')
         //String isValidExpr = "not(" + bindElement.getAttributeNS(NamespaceConstants.XFORMS_NS, "nodeset") + " = '" + pleaseSelect + "')";
         // ->no, not(. = '[Choose State]')
         final String isValidExpr = "not( . = '" + pleaseSelect + "')";

         //check if there was a constraint
         final String constraint = bindElement.getAttributeNS(NamespaceConstants.XFORMS_NS, "constraint");
         bindElement.setAttributeNS(NamespaceConstants.XFORMS_NS,
                                    NamespaceConstants.XFORMS_PREFIX + ":constraint",
                                    (constraint != null && constraint.length() != 0
                                     ? constraint + " && " + isValidExpr
                                     : isValidExpr));
      }

      control.appendChild(choices);
      formSection.appendChild(control);

      // add content to select1
      final Map<String, Element> caseTypes =
         this.addChoicesForSelectSwitchControl(xformsDocument, choices, enumValues);

      //add a trigger for this control (is there a way to not need it ?)
      final Element dispatchTrigger = xformsDocument.createElementNS(NamespaceConstants.XFORMS_NS,
                                                                     NamespaceConstants.XFORMS_PREFIX + ":dispatch");
      dispatchTrigger.setAttributeNS(NamespaceConstants.XFORMS_NS,
                                     NamespaceConstants.XFORMS_PREFIX + ":name",
                                     "DOMActivate");
      dispatchTrigger.setAttributeNS(NamespaceConstants.XFORMS_NS,
                                     NamespaceConstants.XFORMS_PREFIX + ":target",
                                     select1Id);
      formSection.appendChild(this.createTrigger(xformsDocument,
                                                 null,
                                                 null,
                                                 "validate choice",
                                                 dispatchTrigger));

      //add switch
      final Element switchElement = xformsDocument.createElementNS(NamespaceConstants.XFORMS_NS,
                                                                   NamespaceConstants.XFORMS_PREFIX + ":switch");
      this.setXFormsId(switchElement, select1Id + "-" + this.setXFormsId(switchElement));
      switchElement.setAttributeNS(NamespaceConstants.XFORMS_NS,
                                   NamespaceConstants.XFORMS_PREFIX + ":bind",
                                   bindId);

      formSection.appendChild(switchElement);
      //formSection.appendChild(switchElement);

      /////////////// add this type //////////////
      final Element firstCaseElement = caseTypes.get(controlType.getName());
      switchElement.appendChild(firstCaseElement);
      firstCaseElement.setAttributeNS(NamespaceConstants.XFORMS_NS,
                                      NamespaceConstants.XFORMS_PREFIX + ":selected",
                                      "true");

      defaultInstanceElement.setAttributeNS(NamespaceConstants.XMLSCHEMA_INSTANCE_NS,
                                            NamespaceConstants.XMLSCHEMA_INSTANCE_PREFIX + ":type",
                                            controlType.getName());
      defaultInstanceElement.setAttributeNS(NamespaceConstants.XMLSCHEMA_INSTANCE_NS,
                                            NamespaceConstants.XMLSCHEMA_INSTANCE_PREFIX + ":nil",
                                            "true");
      LOGGER.debug("&&&&& die before " + XMLUtil.toString(defaultInstanceElement));
      this.addComplexType(xformsDocument,
                          modelSection,
                          defaultInstanceElement,
                          firstCaseElement,
                          schema,
                          (XSComplexTypeDefinition)controlType,
                          elementDecl,
                          pathToRoot,
                          true,
                          false,
                          resourceBundle);
      LOGGER.debug("&&&&& die after " + XMLUtil.toString(defaultInstanceElement));

      /////////////// add sub types //////////////
      // add each compatible type within
      // a case statement
      for (XSTypeDefinition type : compatibleTypes)
      {
         final String compatibleTypeName = type.getName();

         if (LOGGER.isDebugEnabled())
         {
            LOGGER.debug(type == null
                         ? (">>>addElement: compatible type is null!! type=" +
                            compatibleTypeName + ", targetNamespace=" + this.targetNamespace)
                         : ("   >>>addElement: adding compatible type " + type.getName()));
         }
         if (type == null || type.getTypeCategory() != XSTypeDefinition.COMPLEX_TYPE)
         {
            continue;
         }

         //Element caseElement = (Element) xformsDocument.createElementNS(NamespaceConstants.XFORMS_NS,NamespaceConstants.XFORMS_PREFIX + ":case");
         //caseElement.setAttributeNS(NamespaceConstants.XFORMS_NS,NamespaceConstants.XFORMS_PREFIX + ":id",bindId + "_" + type.getName() +"_case");
         //String case_id=this.setXFormsId(caseElement);
         final Element caseElement = caseTypes.get(type.getName());
         caseElement.setAttributeNS(NamespaceConstants.XFORMS_NS,
                                    NamespaceConstants.XFORMS_PREFIX + ":selected",
                                    "false");

         switchElement.appendChild(caseElement);

         this.addComplexType(xformsDocument,
                             modelSection,
                             defaultInstanceElement,
                             caseElement,
                             schema,
                             (XSComplexTypeDefinition) type,
                             elementDecl,
                             pathToRoot,
                             true,
                             true,
                             resourceBundle);

         // modify bind to add a "relevant" attribute that checks the value of @xsi:type
         if (LOGGER.isDebugEnabled())
         {
            LOGGER.debug(XMLUtil.toString(bindElement2));
         }

         final NodeList binds = bindElement2.getElementsByTagNameNS(NamespaceConstants.XFORMS_NS, "bind");
         for (int i = 0; i < binds.getLength(); i++)
         {
            Element subBind = (Element) binds.item(i);
            String name = subBind.getAttributeNS(NamespaceConstants.XFORMS_NS, "nodeset");

            if (LOGGER.isDebugEnabled())
            {
               LOGGER.debug("Testing sub-bind with nodeset " + name);
            }

            if (!SchemaUtil.isElementDeclaredIn(name, (XSComplexTypeDefinition) type, false) &&
                !SchemaUtil.isAttributeDeclaredIn(name, (XSComplexTypeDefinition) type, false))
            {
               continue;
            }
            if (LOGGER.isDebugEnabled())
            {
               LOGGER.debug("Element/Attribute " + name +
                            " declared in type " + type.getName() +
                            ": adding relevant attribute");
            }

            //test sub types of this type
            //TreeSet subCompatibleTypes = (TreeSet) typeTree.get(type);

            String newRelevant = "../@xsi:type='" + type.getName() + "'";
            if (this.typeTree.containsKey(type.getName()))
            {
               for (XSTypeDefinition otherType : this.typeTree.get(type.getName()))
               {
                  newRelevant = newRelevant + " or ../@xsi:type='" + otherType.getName() + "'";
               }
            }

            //change relevant attribute
            final String relevant = subBind.getAttributeNS(NamespaceConstants.XFORMS_NS, "relevant");
            if (relevant != null && relevant.length() != 0)
            {
               newRelevant = ("(" + relevant +
                              ") and " + newRelevant);
            }
            subBind.setAttributeNS(NamespaceConstants.XFORMS_NS,
                                   NamespaceConstants.XFORMS_PREFIX + ":relevant",
                                   newRelevant);
         }
      }
      return control;
   }

   /**
    */
   private void addGroup(final Document xformsDocument,
                         final Element modelSection,
                         final Element defaultInstanceElement,
                         final Element formSection,
                         final XSModel schema,
                         final XSModelGroup group,
                         final XSComplexTypeDefinition controlType,
                         final XSElementDeclaration owner,
                         final String pathToRoot,
                         final SchemaUtil.Occurance o,
                         final boolean checkIfExtension,
                         final ResourceBundle resourceBundle)
      throws FormBuilderException
   {
      if (group == null)
      {
         return;
      }

      final Element repeatSection = this.addRepeatIfNecessary(xformsDocument,
                                                              modelSection,
                                                              formSection,
                                                              owner.getTypeDefinition(),
                                                              o,
                                                              pathToRoot);

      if (LOGGER.isDebugEnabled())
      {
         LOGGER.debug("addGroup from owner=" + owner.getName() +
                      " and controlType=" + controlType.getName());
      }

      final XSObjectList particles = group.getParticles();
      for (int counter = 0; counter < particles.getLength(); counter++)
      {
         final XSParticle currentNode = (XSParticle)particles.item(counter);
         XSTerm term = currentNode.getTerm();

         if (LOGGER.isDebugEnabled())
         {
            LOGGER.debug("	: next term = " + term.getName());
         }

         final SchemaUtil.Occurance childOccurs = new SchemaUtil.Occurance(currentNode);
         if (term instanceof XSModelGroup)
         {

            if (LOGGER.isDebugEnabled())
            {
               LOGGER.debug("	term is a group");
            }

            this.addGroup(xformsDocument,
                          modelSection,
                          defaultInstanceElement,
                          repeatSection,
                          schema,
                          ((XSModelGroup) term),
                          controlType,
                          owner,
                          pathToRoot,
                          childOccurs,
                          checkIfExtension,
                          resourceBundle);
         }
         else if (term instanceof XSElementDeclaration)
         {
            XSElementDeclaration element = (XSElementDeclaration) term;

            if (LOGGER.isDebugEnabled())
            {
               LOGGER.debug("	term is an element declaration: "
                            + term.getName());
            }

            //special case for types already added because used in an extension
            //do not add it when it comes from an extension !!!
            //-> make a copy from the existing form control
            if (checkIfExtension &&
                SchemaUtil.doesElementComeFromExtension(element, controlType))
            {
               if (LOGGER.isDebugEnabled())
               {
                  LOGGER.debug("This element comes from an extension: recopy form controls.\n Model Section=");
                  LOGGER.debug(XMLUtil.toString(modelSection));
               }

               //find the existing bind Id
               //(modelSection is the enclosing bind of the element)
               NodeList binds = modelSection.getElementsByTagNameNS(NamespaceConstants.XFORMS_NS, "bind");
               String bindId = null;
               for (int i = 0; i < binds.getLength() && bindId == null; i++)
               {
                  Element bind = (Element)binds.item(i);
                  String nodeset = bind.getAttributeNS(NamespaceConstants.XFORMS_NS, "nodeset");
                  if (nodeset != null && nodeset.equals(element.getName()))
                  {
                     bindId = bind.getAttributeNS(null, "id");
                  }
               }

               //find the control
               Element control = null;
               if (bindId != null)
               {
                  if (LOGGER.isDebugEnabled())
                  {
                     LOGGER.debug("bindId found: " + bindId);
                  }

                  final JXPathContext context =
                     JXPathContext.newContext(formSection.getOwnerDocument());
                  final Pointer pointer =
                     context.getPointer("//*[@" + NamespaceConstants.XFORMS_PREFIX + ":bind='" + bindId + "']");
                  if (pointer != null)
                  {
                     control = (Element) pointer.getNode();
                  }
               }

               //copy it
               if (control == null)
               {
                  LOGGER.warn("Corresponding control not found");
               }
               else
               {
                  Element newControl = (Element)control.cloneNode(true);
                  //set new Ids to XForm elements
                  this.resetXFormIds(newControl);

                  repeatSection.appendChild(newControl);
               }
            }
            else
            {
               //add it normally
               final String elementName = this.getElementName(element, xformsDocument);
               final String path = (pathToRoot.length() == 0
                                    ? elementName
                                    : pathToRoot + "/" + elementName);

               final Element newDefaultInstanceElement = xformsDocument.createElement(elementName);
               if (element.getConstraintType() != XSConstants.VC_NONE)
               {
                  Node value = xformsDocument.createTextNode(element.getConstraintValue());
                  newDefaultInstanceElement.appendChild(value);
               }

               this.addElement(xformsDocument,
                               modelSection,
                               newDefaultInstanceElement,
                               repeatSection,
                               schema,
                               element,
                               path,
                               resourceBundle);

               final SchemaUtil.Occurance elementOccurs = SchemaUtil.getOccurance(element);
               LOGGER.debug("adding " + (elementOccurs.maximum == 1
                                         ? 1
                                         : elementOccurs.minimum + 1) +
                            " default instance element for " + elementName +
                            " at path " + path);
               // update the default instance
               if (elementOccurs.isRepeated())
               {
                  LOGGER.debug("adding " + (elementOccurs.minimum + 1) +
                               " default instance elements for " + elementName +
                               " at path " + path);
                  for (int i = 0; i < elementOccurs.minimum + 1; i++)
                  {
                     final Element e = (Element)newDefaultInstanceElement.cloneNode(true);
                     if (i == elementOccurs.minimum)
                     {
                        e.setAttributeNS(NamespaceService.ALFRESCO_URI,
                                         NamespaceService.ALFRESCO_PREFIX + ":prototype",
                                         "true");
                     }
                     defaultInstanceElement.appendChild(e);
                  }
               }
               else
               {
                  LOGGER.debug("adding one default instance element for " + elementName +
                               " at path " + path);
                  if (elementOccurs.minimum == 0)
                  {
                     newDefaultInstanceElement.setAttributeNS(NamespaceConstants.XMLSCHEMA_INSTANCE_NS,
                                                              NamespaceConstants.XMLSCHEMA_INSTANCE_PREFIX + ":nil",
                                                              "true");
                  }
                  defaultInstanceElement.appendChild(newDefaultInstanceElement);
               }
            }
         }
         else
         { //XSWildcard -> ignore ?
            //LOGGER.warn("XSWildcard found in group from "+owner.getName()+" for pathToRoot="+pathToRoot);
         }
      }

      if (LOGGER.isDebugEnabled())
      {
         LOGGER.debug("--- end of addGroup from owner=" + owner.getName());
      }
   }

   /**
    * Add a repeat section if maxOccurs > 1.
    */
   private Element addRepeatIfNecessary(final Document xformsDocument,
                                        final Element modelSection,
                                        final Element formSection,
                                        final XSTypeDefinition controlType,
                                        final SchemaUtil.Occurance o ,
                                        final String pathToRoot)
   {

      // add xforms:repeat section if this element re-occurs
      if (o.maximum == 1)
      {
         return formSection;
      }

      if (LOGGER.isDebugEnabled())
      {
         LOGGER.debug("AddRepeatIfNecessary for multiple element for type " +
                      controlType.getName() + ", maxOccurs=" + o.maximum);
      }

      final Element repeatSection =
         xformsDocument.createElementNS(NamespaceConstants.XFORMS_NS,
                               NamespaceConstants.XFORMS_PREFIX + ":repeat");

      //bind instead of repeat
      //repeatSection.setAttributeNS(NamespaceConstants.XFORMS_NS,NamespaceConstants.XFORMS_PREFIX + ":nodeset",pathToRoot);
      // bind -> last element in the modelSection
      Element bind = DOMUtil.getLastChildElement(modelSection);
      String bindId = null;

      if (bind != null &&
          bind.getLocalName() != null &&
          "bind".equals(bind.getLocalName()))
      {
         bindId = bind.getAttributeNS(null, "id");
      }
      else
      {
         LOGGER.warn("addRepeatIfNecessary: bind not found: " + bind
                     + " (model selection name=" + modelSection.getNodeName() + ")");

         //if no bind is found -> modelSection is already a bind, get its parent last child
         bind = DOMUtil.getLastChildElement(modelSection.getParentNode());

         if (bind != null &&
             bind.getLocalName() != null &&
             "bind".equals(bind.getLocalName()))
         {
            bindId = bind.getAttributeNS(null, "id");
         }
         else
         {
            LOGGER.warn("addRepeatIfNecessary: bind really not found");
         }
      }

      repeatSection.setAttributeNS(NamespaceConstants.XFORMS_NS,
                                   NamespaceConstants.XFORMS_PREFIX + ":bind",
                                   bindId);
      this.setXFormsId(repeatSection);

      //appearance=full is more user friendly
      repeatSection.setAttributeNS(NamespaceConstants.XFORMS_NS,
                                   NamespaceConstants.XFORMS_PREFIX + ":appearance",
                                   "full");

      formSection.appendChild(repeatSection);

      //add a group inside the repeat?
      final Element group = xformsDocument.createElementNS(NamespaceConstants.XFORMS_NS,
                                                  NamespaceConstants.XFORMS_PREFIX + ":group");
      group.setAttributeNS(NamespaceConstants.XFORMS_NS,
                           NamespaceConstants.XFORMS_PREFIX + ":appearance",
                           "repeated");
      this.setXFormsId(group);
      repeatSection.appendChild(group);
      return group;
   }

   /**
    */
   private Element addSimpleType(final Document xformsDocument,
                                 final Element modelSection,
                                 Element formSection,
                                 final XSModel schema,
                                 final XSTypeDefinition controlType,
                                 final String owningElementName,
                                 final XSObject owner,
                                 final String pathToRoot,
                                 final SchemaUtil.Occurance o,
                                 final ResourceBundle resourceBundle)
   {
      if (LOGGER.isDebugEnabled())
      {
         LOGGER.debug("addSimpleType for " + controlType.getName() +
                      " (owningElementName=" + owningElementName + ")");
         if (owner != null)
         {
            LOGGER.debug("owner is " + owner.getClass() +
                         ", name is " + owner.getName());
         }
      }

      // create the <xforms:bind> element and add it to the model.
      Element bindElement = xformsDocument.createElementNS(NamespaceConstants.XFORMS_NS,
                                                           NamespaceConstants.XFORMS_PREFIX + ":bind");
      String bindId = this.setXFormsId(bindElement);
      modelSection.appendChild(bindElement);
      bindElement = this.startBindElement(bindElement, schema, controlType, owner, pathToRoot, o);

      // add a group if a repeat !
      if (owner instanceof XSElementDeclaration && o.maximum != 1)
      {
         final Element groupElement = this.createGroup(xformsDocument,
                                                       modelSection,
                                                       formSection,
                                                       (XSElementDeclaration)owner,
                                                       resourceBundle);
         groupElement.setAttributeNS(NamespaceConstants.XFORMS_NS,
                                     NamespaceConstants.XFORMS_PREFIX + ":appearance",
                                     "repeated");

         //set content
         formSection = groupElement;
      }

      //eventual repeat
      final Element repeatSection = this.addRepeatIfNecessary(xformsDocument,
                                                              modelSection,
                                                              formSection,
                                                              controlType,
                                                              o,
                                                              pathToRoot);

      // create the form control element
      //put a wrapper for the repeat content, but only if it is really a repeat
      if (repeatSection != formSection)
      {
         //if there is a repeat -> create another bind with "."
         final Element bindElement2 =
            xformsDocument.createElementNS(NamespaceConstants.XFORMS_NS,
                                           NamespaceConstants.XFORMS_PREFIX + ":bind");
         final String bindId2 = this.setXFormsId(bindElement2);
         bindElement2.setAttributeNS(NamespaceConstants.XFORMS_NS,
                                     NamespaceConstants.XFORMS_PREFIX + ":nodeset",
                                     ".");
         bindElement.appendChild(bindElement2);
         bindElement = bindElement2;
         bindId = bindId2;
      }

      final String caption = (owner != null
                              ? this.createCaption(owner, resourceBundle)
                              : this.createCaption(owningElementName));
      final Element formControl = this.createFormControl(xformsDocument,
                                                         schema,
                                                         caption,
                                                         controlType,
                                                         owner,
                                                         bindId,
                                                         bindElement,
                                                         o,
                                                         resourceBundle);
      repeatSection.appendChild(formControl);

      // if this is a repeatable then set ref to point to current element
      // not sure if this is a workaround or this is just the way XForms works...
      //
      //if (!repeatSection.equals(formSection))
      //formControl.setAttributeNS(NamespaceConstants.XFORMS_NS,
      //NamespaceConstants.XFORMS_PREFIX + ":ref",
      //".");

      Element hint = this.createHint(xformsDocument, owner, resourceBundle);
      if (hint != null)
      {
         formControl.appendChild(hint);
      }

      //add selector if repeat
      //if (repeatSection != formSection)
      //this.addSelector(xformsDocument, (Element) formControl.getParentNode());

      return formSection;
   }

   private Element addSimpleType(final Document xformsDocument,
                                 final Element modelSection,
                                 final Element formSection,
                                 final XSModel schema,
                                 final XSSimpleTypeDefinition controlType,
                                 final XSElementDeclaration owner,
                                 final String pathToRoot,
                                 final ResourceBundle resourceBundle)
   {
      return this.addSimpleType(xformsDocument,
                                modelSection,
                                formSection,
                                schema,
                                controlType,
                                owner.getName(),
                                owner,
                                pathToRoot,
                                SchemaUtil.getOccurance(owner),
                                resourceBundle);
   }

   private Element addSimpleType(final Document xformsDocument,
                                 final Element modelSection,
                                 final Element formSection,
                                 final XSModel schema,
                                 final XSSimpleTypeDefinition controlType,
                                 final XSAttributeUse owningAttribute,
                                 final String pathToRoot,
                                 final ResourceBundle resourceBundle)
   {
      return this.addSimpleType(xformsDocument,
                                modelSection,
                                formSection,
                                schema,
                                controlType,
                                owningAttribute.getAttrDeclaration().getName(),
                                owningAttribute,
                                pathToRoot,
                                new SchemaUtil.Occurance(owningAttribute.getRequired() ? 1 : 0, 1),
                                resourceBundle);
   }

   private Element createFormControl(final Document xformsDocument,
                                     final XSModel schema,
                                     final String caption,
                                     final XSTypeDefinition controlType,
                                     final XSObject owner,
                                     final String bindId,
                                     final Element bindElement,
                                     final SchemaUtil.Occurance o,
                                     final ResourceBundle resourceBundle)
   {
      Element formControl = null;
      if (controlType.getTypeCategory() == XSTypeDefinition.SIMPLE_TYPE &&
          ((XSSimpleTypeDefinition)controlType).getItemType() != null)
      {
            formControl = this.createControlForListType(xformsDocument,
                                                        (XSSimpleTypeDefinition)controlType,
                                                        caption,
                                                        bindElement,
                                                        resourceBundle);
      }
      else if (controlType.getTypeCategory() == XSTypeDefinition.SIMPLE_TYPE &&
               ((XSSimpleTypeDefinition)controlType).isDefinedFacet(XSSimpleTypeDefinition.FACET_ENUMERATION))
      {
         formControl = this.createControlForEnumerationType(xformsDocument,
                                                            (XSSimpleTypeDefinition)controlType,
                                                            caption,
                                                            bindElement,
                                                            resourceBundle);
      }
      else if (controlType.getTypeCategory() == XSTypeDefinition.COMPLEX_TYPE &&
               "anyType".equals(controlType.getName()))
      {
         formControl = this.createControlForAnyType(xformsDocument, caption, controlType);
      }
      else
      {
         formControl = this.createControlForAtomicType(xformsDocument,
                                                       caption,
                                                       (XSSimpleTypeDefinition)controlType);
      }

      formControl.setAttributeNS(NamespaceConstants.XFORMS_NS,
                                 NamespaceConstants.XFORMS_PREFIX + ":bind",
                                 bindId);

      // TODO: Enhance alert statement based on facet restrictions.
      // TODO: Enhance to support minOccurs > 1 and maxOccurs > 1.
      // TODO: Add i18n/l10n suppport to this - use java MessageFormatter...
      //
      //       e.g. Please provide a valid value for 'Address'. 'Address' is a mandatory decimal field.
      //
      final Element alertElement = xformsDocument.createElementNS(NamespaceConstants.XFORMS_NS,
                                                         NamespaceConstants.XFORMS_PREFIX + ":alert");
      formControl.appendChild(alertElement);
      this.setXFormsId(alertElement);

      String alert = Schema2XForms.extractPropertyFromAnnotation(NamespaceService.ALFRESCO_URI,
                                                                 "alert",
                                                                 this.getAnnotation(owner),
                                                                 resourceBundle);
      if (alert == null)
      {
         alert = ("Please provide a valid value for '" + caption + "'." +
                  " '" + caption +
                  "' is " + (o.minimum == 0 ? "an optional" : "a required") + " '" +
                  createCaption(this.getXFormsTypeName(xformsDocument, schema, controlType)) +
                  "' value.");
      }
      alertElement.appendChild(xformsDocument.createTextNode(alert));
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
         return SchemaUtil.getBuiltInTypeName(controlType);
      }

      //type is globally defined
      //use schema type

      //local type name
      String localTypeName = typeName;
      final int index = typeName.indexOf(':');
      if (index > -1 && typeName.length() > index)
      {
         localTypeName = typeName.substring(index + 1);
      }

      //completeTypeName = new prefix + local name
      String result = localTypeName;
      if (typeNS != null)
      {
         //namespace prefix in this document
         if (LOGGER.isDebugEnabled())
         {
            LOGGER.debug("resolving namespace prefix for uri " + typeNS +
                         " using document element " + context);
         }
         final String prefix = NamespaceResolver.getPrefix(context, typeNS);
         if (prefix != null && prefix.length() != 0)
         {
            result = prefix + ":" + localTypeName;
         }
      }

      if (LOGGER.isDebugEnabled())
      {
         LOGGER.debug("getXFormsTypeName: typeName=" + typeName +
                      ", typeNS=" + typeNS +
                      ", result=" + result);
      }
      return result;
   }

   private Document createFormTemplate(final String formId)
   {
      final Document xformsDocument = XMLUtil.newDocument();

      final Element envelopeElement = xformsDocument.createElementNS(NamespaceConstants.XHTML_NS,
                                                                     NamespaceConstants.XHTML_PREFIX + ":html");
      xformsDocument.appendChild(envelopeElement);

      //set namespace attribute
      this.addNamespace(envelopeElement,
                        NamespaceConstants.XHTML_PREFIX,
                        NamespaceConstants.XHTML_NS);
      this.addNamespace(envelopeElement,
                        NamespaceConstants.XFORMS_PREFIX,
                        NamespaceConstants.XFORMS_NS);
      this.addNamespace(envelopeElement,
                        NamespaceConstants.XMLEVENTS_PREFIX,
                        NamespaceConstants.XMLEVENTS_NS);
      this.addNamespace(envelopeElement,
                        NamespaceConstants.XMLSCHEMA_INSTANCE_PREFIX,
                        NamespaceConstants.XMLSCHEMA_INSTANCE_NS);
      this.addNamespace(envelopeElement,
                        NamespaceService.ALFRESCO_PREFIX,
                        NamespaceService.ALFRESCO_URI);

      //base
      if (this.base != null && this.base.length() != 0)
      {
         envelopeElement.setAttributeNS(NamespaceConstants.XML_NS,
                                        NamespaceConstants.XML_PREFIX + ":base",
                                        this.base);
      }

      //model element
      Element modelElement = xformsDocument.createElementNS(NamespaceConstants.XFORMS_NS,
                                                            NamespaceConstants.XFORMS_PREFIX + ":model");
      this.setXFormsId(modelElement);
      Element modelWrapper = xformsDocument.createElementNS(NamespaceConstants.XHTML_NS,
                                                            NamespaceConstants.XHTML_PREFIX + ":head");
      modelWrapper.appendChild(modelElement);
      envelopeElement.appendChild(modelWrapper);

      //form control wrapper -> created by wrapper
      //Element formWrapper = xformsDocument.createElement("body");
      //envelopeElement.appendChild(formWrapper);
      Element formWrapper = xformsDocument.createElementNS(NamespaceConstants.XHTML_NS,
                                                           NamespaceConstants.XHTML_PREFIX + ":body");
      envelopeElement.appendChild(formWrapper);
      return xformsDocument;
   }

   private Element createGroup(final Document xformsDocument,
                               final Element modelSection,
                               final Element formSection,
                               final XSElementDeclaration owner,
                               final ResourceBundle resourceBundle)
   {
      // add a group node and recurse
      Element groupElement =
         xformsDocument.createElementNS(NamespaceConstants.XFORMS_NS,
                                        NamespaceConstants.XFORMS_PREFIX + ":group");
      this.setXFormsId(groupElement);
      groupElement.setAttributeNS(NamespaceConstants.XFORMS_NS,
                                  NamespaceConstants.XFORMS_PREFIX + ":appearance",
                                  "full");

      //groupElement = (Element) formSection.appendChild(groupElement);
      formSection.appendChild(groupElement);
      groupElement.appendChild(this.createLabel(xformsDocument,
                                                this.createCaption(owner, resourceBundle)));
      return groupElement;
   }

   public String createCaption(final String text,
                               final XSObject o,
                               final ResourceBundle resourceBundle)
   {
      return this.createCaption(text, this.getAnnotation(o), resourceBundle);
   }

   public String createCaption(final String text,
                               final XSAnnotation annotation,
                               final ResourceBundle resourceBundle)
   {
      final String s = Schema2XForms.extractPropertyFromAnnotation(NamespaceService.ALFRESCO_URI,
                                                                   "label",
                                                                   annotation,
                                                                   resourceBundle);
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
   public String createCaption(final XSAttributeDeclaration attribute,
                               final ResourceBundle resourceBundle)
   {
      return this.createCaption(attribute.getName(), attribute, resourceBundle);
   }

   public String createCaption(final XSAttributeUse attribute,
                               final ResourceBundle resourceBundle)
   {
      return this.createCaption(attribute.getAttrDeclaration().getName(), attribute, resourceBundle);
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
   public String createCaption(final XSElementDeclaration element,
                               final ResourceBundle resourceBundle)
   {
      return this.createCaption(element.getName(), element, resourceBundle);
   }

   public String createCaption(final XSObject element,
                               final ResourceBundle resourceBundle)
   {
      if (element instanceof XSElementDeclaration)
         return this.createCaption((XSElementDeclaration)element, resourceBundle);
      if (element instanceof XSAttributeDeclaration)
         return this.createCaption((XSAttributeDeclaration)element, resourceBundle);
      if (element instanceof XSAttributeUse)
         return this.createCaption((XSAttributeUse)element, resourceBundle);

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
    * @param xformsDocument       The XForm document.
    * @param controlType The XML Schema type for which the form control is to be created.
    * @return The element for the form control.
    */
   public Element createControlForAnyType(final Document xformsDocument,
                                          final String caption,
                                          final XSTypeDefinition controlType)
   {
      Element control = xformsDocument.createElementNS(NamespaceConstants.XFORMS_NS,
                                                       NamespaceConstants.XFORMS_PREFIX + ":textarea");
      this.setXFormsId(control);
//      control.setAttributeNS(Schema2XForms.CHIBA_NS,
//                             Schema2XForms.CHIBA_PREFIX + "height",
//                             "3");

      control.appendChild(this.createLabel(xformsDocument, caption));

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
    * @param xformsDocument       The XForm document.
    * @param controlType The XML Schema type for which the form control is to be created.
    * @return The element for the form control.
    */
   public Element createControlForAtomicType(Document xformsDocument,
                                             String caption,
                                             XSSimpleTypeDefinition controlType)
   {
      Element control;

      if ("boolean".equals(controlType.getName()))
      {
         control = xformsDocument.createElementNS(NamespaceConstants.XFORMS_NS,
                                         NamespaceConstants.XFORMS_PREFIX + ":select1");
         this.setXFormsId(control);
         final String[] values = { "true", "false" };
         for (String v : values)
         {
            final Element item = this.createXFormsItem(xformsDocument, v, v);
            control.appendChild(item);
         }
      }
      else if ("anyURI".equals(controlType.getName()))
      {
         control = xformsDocument.createElementNS(NamespaceConstants.XFORMS_NS,
                                         NamespaceConstants.XFORMS_PREFIX + ":upload");
         final Element e = xformsDocument.createElementNS(NamespaceConstants.XFORMS_NS,
                                                 NamespaceConstants.XFORMS_PREFIX + ":filename");
         control.appendChild(e);
         e.setAttributeNS(NamespaceConstants.XFORMS_NS,
                          NamespaceConstants.XFORMS_PREFIX + ":ref",
                          ".");
         this.setXFormsId(control);
      }
      else
      {
         control = xformsDocument.createElementNS(NamespaceConstants.XFORMS_NS, NamespaceConstants.XFORMS_PREFIX + ":input");
         this.setXFormsId(control);
      }

      control.appendChild(this.createLabel(xformsDocument, caption));

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
    * @param xformsDocument       The XForm document.
    * @param controlType The XML Schema type for which the form control is to be created.
    * @param caption     The caption for the form control. The caller The purpose of providing the caption
    *                    is to permit the implementation to add a <b>[Select1 .... ]</b> message that involves the caption.
    * @param bindElement The bind element for this control. The purpose of providing the bind element
    *                    is to permit the implementation to add a isValid attribute to the bind element that prevents
    *                    the <b>[Select1 .... ]</b> item from being selected.
    * @return The element for the form control.
    */
   public Element createControlForEnumerationType(final Document xformsDocument,
                                                  final XSSimpleTypeDefinition controlType,
                                                  final String caption,
                                                  final Element bindElement,
                                                  final ResourceBundle resourceBundle)
   {
      // TODO: Figure out an intelligent or user determined way to decide between
      // selectUI style (listbox, menu, combobox, radio) (radio and listbox best apply)
      // Possibly look for special appInfo section in the schema and if not present default to comboBox...
      //
      // For now, use radio if enumValues < DEFAULT_LONG_LIST_MAX_SIZE otherwise use combobox
      //
      final StringList enumFacets = controlType.getLexicalEnumeration();
      if (enumFacets.getLength() <= 0)
      {
         return null;
      }

      Element control = xformsDocument.createElementNS(NamespaceConstants.XFORMS_NS,
                                              NamespaceConstants.XFORMS_PREFIX + ":select1");
      this.setXFormsId(control);

      //label
      control.appendChild(this.createLabel(xformsDocument, caption));

      Element choices = xformsDocument.createElementNS(NamespaceConstants.XFORMS_NS,
                                                       NamespaceConstants.XFORMS_PREFIX + ":choices");
      this.setXFormsId(choices);

      final XSObjectList mvFacets = controlType.getMultiValueFacets();
      if (mvFacets.getLength() != 1)
         throw new RuntimeException("expected exactly one MultiValueFacet for " + controlType);

      final XSObjectList annotations =
         ((XSMultiValueFacet)mvFacets.item(0)).getAnnotations();

      final Map<String, XSAnnotation> enumValues =
         new LinkedHashMap<String, XSAnnotation>(enumFacets.getLength());
      for (int i = 0; i < enumFacets.getLength(); i++)
      {
         enumValues.put(enumFacets.item(i),
                        (annotations.getLength() == enumFacets.getLength()
                         ? (XSAnnotation)annotations.item(i)
                         : null));
      }

      control.setAttributeNS(NamespaceConstants.XFORMS_NS,
                             NamespaceConstants.XFORMS_PREFIX + ":appearance",
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
         final Element item = this.createXFormsItem(xformsDocument, pleaseSelect, pleaseSelect);
         choices.appendChild(item);

         // not(purchaseOrder/state = '[Choose State]')
         //String isValidExpr = "not(" + bindElement.getAttributeNS(NamespaceConstants.XFORMS_NS,"nodeset") + " = '" + pleaseSelect + "')";
         // ->no, not(. = '[Choose State]')
         final String isValidExpr = "not( . = '" + pleaseSelect + "')";

         //check if there was a constraint
         String constraint = bindElement.getAttributeNS(NamespaceConstants.XFORMS_NS, "constraint");

         constraint = (constraint != null && constraint.length() != 0
                       ? constraint + " and " + isValidExpr
                       : isValidExpr);

         bindElement.setAttributeNS(NamespaceConstants.XFORMS_NS,
                                    NamespaceConstants.XFORMS_PREFIX + ":constraint",
                                    constraint);
      }

      control.appendChild(choices);

      this.addChoicesForSelectControl(xformsDocument, choices, enumValues, resourceBundle);

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
    * @param xformsDocument       The XForm document.
    * @param listType    The XML Schema list type for which the form control is to be created.
    * @param caption     The caption for the form control. The caller The purpose of providing the caption
    *                    is to permit the implementation to add a <b>[Select1 .... ]</b> message that involves the caption.
    * @param bindElement The bind element for this control. The purpose of providing the bind element
    *                    is to permit the implementation to add a isValid attribute to the bind element that prevents
    *                    the <b>[Select1 .... ]</b> item from being selected.
    * @return The element for the form control.
    */
   public Element createControlForListType(final Document xformsDocument,
                                           final XSSimpleTypeDefinition listType,
                                           final String caption,
                                           final Element bindElement,
                                           final ResourceBundle resourceBundle)
   {
      XSSimpleTypeDefinition controlType = listType.getItemType();

      final StringList enumFacets = controlType.getLexicalEnumeration();
      if (enumFacets.getLength() <= 0)
         return null;
      Element control = xformsDocument.createElementNS(NamespaceConstants.XFORMS_NS,
                                              NamespaceConstants.XFORMS_PREFIX + ":select");
      this.setXFormsId(control);

      control.appendChild(this.createLabel(xformsDocument, caption));


      final XSObjectList mvFacets = controlType.getMultiValueFacets();
      if (mvFacets.getLength() != 1)
         throw new RuntimeException("expected exactly one MultiValueFacet for " + controlType);

      final XSObjectList annotations =
         ((XSMultiValueFacet)mvFacets.item(0)).getAnnotations();

      final Map<String, XSAnnotation> enumValues =
         new LinkedHashMap<String, XSAnnotation>(enumFacets.getLength());
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
      control.setAttributeNS(NamespaceConstants.XFORMS_NS,
                             NamespaceConstants.XFORMS_PREFIX + ":appearance",
                             (enumValues.size() < Long.parseLong(getProperty(SELECTMANY_LONG_LIST_SIZE_PROP))
                              ? getProperty(SELECTMANY_UI_CONTROL_SHORT_PROP)
                              : getProperty(SELECTMANY_UI_CONTROL_LONG_PROP)));
      Element choices = xformsDocument.createElementNS(NamespaceConstants.XFORMS_NS,
                                              NamespaceConstants.XFORMS_PREFIX + ":choices");
      this.setXFormsId(choices);
      control.appendChild(choices);

      this.addChoicesForSelectControl(xformsDocument, choices, enumValues, resourceBundle);

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
   public Element createHint(final Document xformsDocument,
                             final XSObject node,
                             final ResourceBundle resourceBundle)
   {
      final XSAnnotation annotation = this.getAnnotation(node);
      if (annotation == null)
         return null;
      final String s = this.extractPropertyFromAnnotation(NamespaceService.ALFRESCO_URI,
                                                          "hint",
                                                          annotation,
                                                          resourceBundle);
      if (s == null)
         return null;
      final Element hintElement =
         xformsDocument.createElementNS(NamespaceConstants.XFORMS_NS,
                                        NamespaceConstants.XFORMS_PREFIX + ":hint");
      this.setXFormsId(hintElement);
      hintElement.appendChild(xformsDocument.createTextNode(s));
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
                                   final SchemaUtil.Occurance o)
   {
      // START WORKAROUND
      // Due to a Chiba bug, anyType is not a recognized type name.
      // so, if this is an anyType, then we'll just skip the type
      // setting.
      //
      // type.getName() may be 'null' for anonymous types, so compare against
      // static string (see bug #1172541 on sf.net)

      String nodeset = pathToRoot;
      if (o.isRepeated())
      {
          nodeset = pathToRoot + "[position() != last()]";
      }

      bindElement.setAttributeNS(NamespaceConstants.XFORMS_NS,
                                 NamespaceConstants.XFORMS_PREFIX + ":nodeset",
                                 nodeset);

      if (!"anyType".equals(controlType.getName()) &&
          controlType instanceof XSSimpleTypeDefinition)
      {
         String typeName = this.getXFormsTypeName(bindElement.getOwnerDocument(),
                                                  schema,
                                                  controlType);
         if (typeName != null && typeName.length() != 0)
         {
            bindElement.setAttributeNS(NamespaceConstants.XFORMS_NS,
                                       NamespaceConstants.XFORMS_PREFIX + ":type",
                                       typeName);
         }
      }

      final short constraintType =
         (owner != null && owner instanceof XSElementDeclaration
          ? ((XSElementDeclaration)owner).getConstraintType()
          : (owner != null && owner instanceof XSAttributeDeclaration
             ? ((XSAttributeDeclaration)owner).getConstraintType()
             : (owner != null && owner instanceof XSAttributeUse
                ? ((XSAttributeUse)owner).getConstraintType()
                : XSConstants.VC_NONE)));

      bindElement.setAttributeNS(NamespaceConstants.XFORMS_NS,
                                 NamespaceConstants.XFORMS_PREFIX + ":readonly",
                                 (constraintType == XSConstants.VC_FIXED) + "()");

      if (controlType instanceof XSSimpleTypeDefinition)
      {
         bindElement.setAttributeNS(NamespaceConstants.XFORMS_NS,
                                    NamespaceConstants.XFORMS_PREFIX + ":required",
                                    (o.minimum != 0) + "()");
      }

      //no more minOccurs & maxOccurs element: add a constraint if maxOccurs>1:
      //count(.) <= maxOccurs && count(.) >= minOccurs
      String minConstraint = null;
      String maxConstraint = null;

      if (o.minimum > 1)
      {
         //if 0 or 1 -> no constraint (managed by "required")
         minConstraint = "count(.) >= " + o.minimum;
         bindElement.setAttributeNS(NamespaceService.ALFRESCO_URI,
                                    NamespaceService.ALFRESCO_PREFIX + ":minimum",
                                    String.valueOf(o.minimum));
      }
      if (o.maximum > 1)
      {
         //if 1 or unbounded -> no constraint
         maxConstraint = "count(.) <= " + o.maximum;
         bindElement.setAttributeNS(NamespaceService.ALFRESCO_URI,
                                    NamespaceService.ALFRESCO_PREFIX + ":maximum",
                                    String.valueOf(o.maximum));
      }

      final String constraint = (minConstraint != null && maxConstraint != null
                                 ? minConstraint + " and " + maxConstraint
                                 : (minConstraint != null
                                    ? minConstraint
                                    : maxConstraint));
      if (constraint != null)
         bindElement.setAttributeNS(NamespaceConstants.XFORMS_NS,
                                    NamespaceConstants.XFORMS_PREFIX + ":constraint",
                                    constraint);
      return bindElement;
   }

   /**
    * Get a fully qualified name for this element, and eventually declares a new prefix for the namespace if
    * it was not declared before
    *
    * @param element
    * @param xformsDocument
    * @return
    */
   private String getElementName(final XSElementDeclaration element,
                                 final Document xformsDocument)
   {
      String elementName = element.getName();
      String namespace = element.getNamespace();
      if (namespace != null && namespace.length() != 0)
      {
         String prefix;
         if ((prefix = (String) namespacePrefixes.get(namespace)) == null)
         {
            String basePrefix = (namespace.substring(namespace.lastIndexOf('/', namespace.length()-2)+1));
            int i = 1;
            prefix = basePrefix;
            while (namespacePrefixes.containsValue(prefix))
            {
               prefix = basePrefix + (i++);
            }
            namespacePrefixes.put(namespace, prefix);
            xformsDocument.getDocumentElement().setAttributeNS(NamespaceConstants.XMLNS_NS,
                                                               NamespaceConstants.XMLNS_PREFIX + ':' + prefix,
                                                               namespace);
         }
         elementName = prefix + ":" + elementName;
      }
      return elementName;
   }

   private void addNamespace(final Element e,
                             final String nsPrefix,
                             final String ns)
   {

      if (!e.hasAttributeNS(NamespaceConstants.XMLNS_NS, nsPrefix))
      {
         LOGGER.debug("adding namespace " + ns + " to " + e.getNodeType() + "(" + e.getNodeName() + ")");
         e.setAttributeNS(NamespaceConstants.XMLNS_NS,
                          NamespaceConstants.XMLNS_PREFIX + ':' + nsPrefix,
                          ns);
      }
   }

   private void createTriggersForRepeats(final Document xformsDocument, final Element rootGroup)
   {
      LOGGER.debug("creating triggers for repeats");
      final HashMap<String, Element> bindIdToBind = new HashMap<String, Element>();
      final NodeList binds = xformsDocument.getElementsByTagNameNS(NamespaceConstants.XFORMS_NS, "bind");
      for (int i = 0; i < binds.getLength(); i++)
      {
         final Element b = (Element)binds.item(i);
         LOGGER.debug("adding bind " + b.getAttributeNS(null, "id"));
         bindIdToBind.put(b.getAttributeNS(null, "id"), b);
      }

      final NodeList repeats = xformsDocument.getElementsByTagNameNS(NamespaceConstants.XFORMS_NS, "repeat");
      final HashMap<Element, Element> bindToRepeat = new HashMap<Element, Element>();
      for (int i = 0; i < repeats.getLength(); i++)
      {
         Element r = (Element)repeats.item(i);
         LOGGER.debug("processing repeat " + r.getAttributeNS(null, "id"));
         Element bind = bindIdToBind.get(r.getAttributeNS(NamespaceConstants.XFORMS_NS, "bind"));
         bindToRepeat.put(bind, r);

         String xpath = "";

         do
         {
            if (xpath.length() != 0)
            {
               xpath = '/' + xpath;
            }

            LOGGER.debug("walking bind " + bind.getAttributeNS(null, "id"));
            String s = bind.getAttributeNS(NamespaceConstants.XFORMS_NS, "nodeset");
            s = s.replaceAll("^([^\\[]+).*$", "$1");
            if (bindToRepeat.containsKey(bind) && !r.equals(bindToRepeat.get(bind)))
            {
               s += "[index(\'" + bindToRepeat.get(bind).getAttributeNS(null, "id") + "\')]";
            }
            xpath = s + xpath;
            bind = ((NamespaceConstants.XFORMS_PREFIX + ":bind").equals(bind.getParentNode().getNodeName())
                    ? (Element)bind.getParentNode()
                    : null);
         }
         while (bind != null);
         this.createTriggersForRepeat(xformsDocument,
                                      rootGroup,
                                      r.getAttributeNS(null, "id"),
                                      xpath,
                                      r.getAttributeNS(NamespaceConstants.XFORMS_NS, "bind"));
      }
   }

   private Element createTrigger(final Document xformsDocument,
                                 final String id,
                                 final String bindId,
                                 final String label,
                                 final Element... actions)
   {
      final Element trigger =
         xformsDocument.createElementNS(NamespaceConstants.XFORMS_NS,
                                        NamespaceConstants.XFORMS_PREFIX + ":trigger");
      this.setXFormsId(trigger, id != null ? id : null);

      //copy the bind attribute
      if (bindId != null)
      {
         trigger.setAttributeNS(NamespaceConstants.XFORMS_NS,
                                NamespaceConstants.XFORMS_PREFIX + ":bind",
                                bindId);
      }
      trigger.appendChild(this.createLabel(xformsDocument, label));

      //insert action
      final Element actionWrapper =
         xformsDocument.createElementNS(NamespaceConstants.XFORMS_NS,
                                        NamespaceConstants.XFORMS_PREFIX + ":action");
      trigger.appendChild(actionWrapper);

      for (final Element action : actions)
      {
         actionWrapper.appendChild(action);
         this.setXFormsId(action);
      }
      return trigger;
   }


   /**
    * add triggers to use the repeat elements (allow to add an element, ...)
    */
   private void createTriggersForRepeat(final Document xformsDocument,
                                        final Element rootGroup,
                                        final String repeatId,
                                        final String nodeset,
                                        final String bindId)
   {
      //xforms:at = xforms:index from the "id" attribute on the repeat element
      //trigger insert
      Element action =
         xformsDocument.createElementNS(NamespaceConstants.XFORMS_NS,
                                        NamespaceConstants.XFORMS_PREFIX + ":insert");
      action.setAttributeNS(NamespaceConstants.XFORMS_NS,
                            NamespaceConstants.XFORMS_PREFIX + ":nodeset",
                            nodeset);
      action.setAttributeNS(NamespaceConstants.XFORMS_NS,
                            NamespaceConstants.XFORMS_PREFIX + ":position",
                            "before");
      action.setAttributeNS(NamespaceConstants.XFORMS_NS,
                            NamespaceConstants.XFORMS_PREFIX + ":at",
                            "1");

      final Element trigger_insert_before = this.createTrigger(xformsDocument,
                                                               repeatId + "-insert_before",
                                                               bindId,
                                                               "insert at beginning",
                                                               action);
      
      action = xformsDocument.createElementNS(NamespaceConstants.XFORMS_NS,
                                     NamespaceConstants.XFORMS_PREFIX + ":insert");
      action.setAttributeNS(NamespaceConstants.XFORMS_NS,
                            NamespaceConstants.XFORMS_PREFIX + ":nodeset",
                            nodeset);
      action.setAttributeNS(NamespaceConstants.XFORMS_NS,
                            NamespaceConstants.XFORMS_PREFIX + ":position",
                            "after");
      action.setAttributeNS(NamespaceConstants.XFORMS_NS,
                            NamespaceConstants.XFORMS_PREFIX + ":at",
                            NamespaceConstants.XFORMS_PREFIX + ":index('" + repeatId + "')");

      final Element trigger_insert_after = this.createTrigger(xformsDocument,
                                                              repeatId + "-insert_after",
                                                              bindId,
                                                              "insert after selected",
                                                              action);

      //trigger delete
      action = xformsDocument.createElementNS(NamespaceConstants.XFORMS_NS,
                                              NamespaceConstants.XFORMS_PREFIX + ":delete");
      action.setAttributeNS(NamespaceConstants.XFORMS_NS,
                            NamespaceConstants.XFORMS_PREFIX + ":nodeset",
                            nodeset);
      action.setAttributeNS(NamespaceConstants.XFORMS_NS,
                            NamespaceConstants.XFORMS_PREFIX + ":at",
                            NamespaceConstants.XFORMS_PREFIX + ":index('" + repeatId + "')");

      final Element trigger_delete = this.createTrigger(xformsDocument,
                                                        repeatId != null ? repeatId + "-delete" : null,
                                                        bindId,
                                                        "delete selected",
                                                        action);

      //add the triggers
      rootGroup.appendChild(trigger_insert_before);
      rootGroup.appendChild(trigger_insert_after);
      rootGroup.appendChild(trigger_delete);
   }

   private Element createSubmissionElement(final Document xformDocument,
                                           final String id,
                                           final boolean validate)
   {
      final Element result = xformDocument.createElementNS(NamespaceConstants.XFORMS_NS,
                                                           NamespaceConstants.XFORMS_PREFIX + ":submission");

      this.setXFormsId(result, id);

      result.setAttributeNS(NamespaceConstants.XFORMS_NS,
                            NamespaceConstants.XFORMS_PREFIX + ":validate",
                            validate ? "true" : "false");

      result.setAttributeNS(NamespaceConstants.XFORMS_NS,
                            NamespaceConstants.XFORMS_PREFIX + ":action",
                            this.action == null ? "" : this.base + this.action);

      result.setAttributeNS(NamespaceConstants.XFORMS_NS,
                            NamespaceConstants.XFORMS_PREFIX + ":method",
                            (this.submitMethod != null
                             ? this.submitMethod
                             : Schema2XForms.SubmitMethod.POST).toString());
      return result;
   }

   private Element createSubmitControl(final Document xformsDocument,
                                       final Element submission,
                                       final String id,
                                       final String label)
   {
      final Element result = xformsDocument.createElementNS(NamespaceConstants.XFORMS_NS,
                                                            NamespaceConstants.XFORMS_PREFIX + ":submit");
      result.setAttributeNS(NamespaceConstants.XFORMS_NS,
                            NamespaceConstants.XFORMS_PREFIX + ":submission",
                            submission.getAttributeNS(null, "id"));
      this.setXFormsId(result, id);

      result.appendChild(this.createLabel(xformsDocument, label));

      return result;
   }

   private void createSubmitElements(final Document xformsDocument,
                                     final Element modelSection,
                                     final Element rootGroup)
   {

      Element submission = this.createSubmissionElement(xformsDocument, "submission-validate", true);
      modelSection.appendChild(submission);

      Element submit = this.createSubmitControl(xformsDocument,
                                                submission,
                                                "submit",
                                                "Submit");
      rootGroup.appendChild(submit);

      submission = this.createSubmissionElement(xformsDocument, "submission-draft", false);
      modelSection.appendChild(submission);

      submit = this.createSubmitControl(xformsDocument,
                                        submission,
                                        "save-draft",
                                        "Save Draft");
      rootGroup.appendChild(submit);
   }

   private Element createXFormsItem(final Document xformsDocument,
                                    final String label,
                                    final String value)
   {
      final Element item = xformsDocument.createElementNS(NamespaceConstants.XFORMS_NS,
                                                          NamespaceConstants.XFORMS_PREFIX + ":item");
      this.setXFormsId(item);
      item.appendChild(this.createLabel(xformsDocument, label));

      final Element e = xformsDocument.createElementNS(NamespaceConstants.XFORMS_NS,
                                                       NamespaceConstants.XFORMS_PREFIX + ":value");
      this.setXFormsId(e);
      e.appendChild(xformsDocument.createTextNode(value));
      item.appendChild(e);
      return item;
   }

   private Element createLabel(final Document xformsDocument,
                               final String label)
   {
      final Element e = xformsDocument.createElementNS(NamespaceConstants.XFORMS_NS,
                                                       NamespaceConstants.XFORMS_PREFIX + ":label");
      this.setXFormsId(e);
      e.appendChild(xformsDocument.createTextNode(label));
      return e;
   }
}