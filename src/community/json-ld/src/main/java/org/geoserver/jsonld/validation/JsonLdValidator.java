/* (c) 2019 Open Source Geospatial Foundation - all rights reserved
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.jsonld.validation;

import java.io.IOException;
import org.geoserver.catalog.FeatureTypeInfo;
import org.geoserver.jsonld.builders.JsonBuilder;
import org.geoserver.jsonld.builders.SourceBuilder;
import org.geoserver.jsonld.builders.impl.DynamicValueBuilder;
import org.geoserver.jsonld.builders.impl.IteratingBuilder;
import org.geoserver.jsonld.builders.impl.JsonBuilderContext;
import org.geoserver.jsonld.builders.impl.RootBuilder;
import org.geoserver.jsonld.expressions.ExpressionsUtils;
import org.geoserver.jsonld.expressions.XPathFunction;
import org.geotools.filter.AttributeExpressionImpl;
import org.geotools.filter.FunctionExpressionImpl;
import org.geotools.filter.LiteralExpressionImpl;
import org.opengis.filter.expression.Expression;
import org.opengis.filter.expression.PropertyName;
import org.xml.sax.helpers.NamespaceSupport;

/**
 * This class perform a validation of the json-ld template by evaluating dynamic and source fields
 * using {@link ValidateExpressionVisitor}
 */
public class JsonLdValidator {

    private ValidateExpressionVisitor visitor;

    private FeatureTypeInfo type;

    private String failingAttribute;

    public JsonLdValidator(FeatureTypeInfo type) {
        visitor = new ValidateExpressionVisitor();
        this.type = type;
    }

    public boolean validateTemplate(RootBuilder root) {
        try {
            return validateExpressions(root, new JsonBuilderContext(type.getFeatureType()));
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }

    private boolean validateExpressions(JsonBuilder builder, JsonBuilderContext context) {
        for (JsonBuilder jb : builder.getChildren()) {
            if (jb instanceof DynamicValueBuilder) {
                DynamicValueBuilder djb = (DynamicValueBuilder) jb;
                if (djb.getCql() != null) {
                    try {
                        PropertyName pn = extractXpath(djb.getCql());
                        if (pn != null && pn.accept(visitor, context) == null) {
                            failingAttribute =
                                    "Key: " + djb.getKey() + " Value: " + djb.getCql().toString();
                            return false;
                        }
                    } catch (Exception e) {
                        failingAttribute = "Exception: " + e.getMessage();
                        return false;
                    }
                } else if (djb.getXpath() != null) {
                    try {
                        if (djb.getXpath().accept(visitor, context) == null) {
                            failingAttribute =
                                    "Key: "
                                            + djb.getKey()
                                            + " Value: "
                                            + ((PropertyName) djb.getXpath()).getPropertyName();
                            return false;
                        }
                    } catch (Exception e) {
                        failingAttribute = "Exception: " + e.getMessage();
                        return false;
                    }
                }
            } else if (jb instanceof SourceBuilder) {
                Object newType = null;
                SourceBuilder sb = ((SourceBuilder) jb);
                if (sb.getSource() != null) {
                    String typeName =
                            sb.getStrSource().substring(sb.getStrSource().indexOf(":") + 1);
                    if (!type.getName().contains(typeName)) {
                        newType = sb.getSource().accept(visitor, context);
                        if (newType == null) {
                            failingAttribute = "Source: " + sb.getStrSource();
                            return false;
                        }
                    }
                } else {
                    if (sb instanceof IteratingBuilder) return false;
                }
                if (newType != null) {
                    JsonBuilderContext newContext = new JsonBuilderContext(newType);
                    newContext.setParent(context);
                    context = newContext;
                }
                return validateExpressions(jb, context);
            }
        }
        return true;
    }

    public String getFailingAttribute() {
        return failingAttribute;
    }

    private PropertyName extractXpath(Expression expression) {
        NamespaceSupport namespaces = null;
        try {
            namespaces = ExpressionsUtils.declareNamespaces(type.getFeatureType());
        } catch (IOException e) {
            throw new RuntimeException("Unable to retrieve FeatureType for " + type.getName());
        }
        PropertyName pn = null;
        if (expression instanceof AttributeExpressionImpl) {
            pn = (AttributeExpressionImpl) expression;
        } else if (expression instanceof XPathFunction) {
            XPathFunction xpath = (XPathFunction) expression;
            LiteralExpressionImpl param = (LiteralExpressionImpl) xpath.getParameters().get(0);
            pn = new AttributeExpressionImpl(String.valueOf(param.getValue()), namespaces);
        } else if (expression instanceof FunctionExpressionImpl) {
            FunctionExpressionImpl function = (FunctionExpressionImpl) expression;
            for (Expression ex : function.getParameters()) {
                if (pn == null) pn = extractXpath(ex);
                else break;
            }
        }
        return pn;
    }
}
