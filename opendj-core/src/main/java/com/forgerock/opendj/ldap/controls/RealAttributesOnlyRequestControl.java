/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License, Version 1.0 only
 * (the "License").  You may not use this file except in compliance
 * with the License.
 *
 * You can obtain a copy of the license at legal-notices/CDDLv1_0.txt
 * or http://forgerock.org/license/CDDLv1.0.html.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at legal-notices/CDDLv1_0.txt.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information:
 *      Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 *
 *
 *      Copyright 2010 Sun Microsystems, Inc.
 */
package com.forgerock.opendj.ldap.controls;

import static com.forgerock.opendj.ldap.CoreMessages.ERR_REAL_ATTRS_ONLY_CONTROL_BAD_OID;
import static com.forgerock.opendj.ldap.CoreMessages.ERR_REAL_ATTRS_ONLY_INVALID_CONTROL_VALUE;

import org.forgerock.i18n.LocalizableMessage;
import org.forgerock.opendj.ldap.ByteString;
import org.forgerock.opendj.ldap.DecodeException;
import org.forgerock.opendj.ldap.DecodeOptions;
import org.forgerock.opendj.ldap.controls.Control;
import org.forgerock.opendj.ldap.controls.ControlDecoder;

import org.forgerock.util.Reject;

/**
 * The Sun-defined real attributes only request control. The OID for this
 * control is 2.16.840.1.113730.3.4.17, and it does not have a value.
 */
public final class RealAttributesOnlyRequestControl implements Control {
    /**
     * The OID for the real attributes only request control.
     */
    public static final String OID = "2.16.840.1.113730.3.4.17";

    private static final RealAttributesOnlyRequestControl CRITICAL_INSTANCE =
            new RealAttributesOnlyRequestControl(true);

    private static final RealAttributesOnlyRequestControl NONCRITICAL_INSTANCE =
            new RealAttributesOnlyRequestControl(false);

    /**
     * A decoder which can be used for decoding the real attributes only request
     * control.
     */
    public static final ControlDecoder<RealAttributesOnlyRequestControl> DECODER =
            new ControlDecoder<RealAttributesOnlyRequestControl>() {

                public RealAttributesOnlyRequestControl decodeControl(final Control control,
                        final DecodeOptions options) throws DecodeException {
                    Reject.ifNull(control);

                    if (control instanceof RealAttributesOnlyRequestControl) {
                        return (RealAttributesOnlyRequestControl) control;
                    }

                    if (!control.getOID().equals(OID)) {
                        final LocalizableMessage message =
                                ERR_REAL_ATTRS_ONLY_CONTROL_BAD_OID.get(control.getOID(), OID);
                        throw DecodeException.error(message);
                    }

                    if (control.hasValue()) {
                        final LocalizableMessage message =
                                ERR_REAL_ATTRS_ONLY_INVALID_CONTROL_VALUE.get();
                        throw DecodeException.error(message);
                    }

                    return control.isCritical() ? CRITICAL_INSTANCE : NONCRITICAL_INSTANCE;
                }

                public String getOID() {
                    return OID;
                }
            };

    /**
     * Creates a new real attributes only request control having the provided
     * criticality.
     *
     * @param isCritical
     *            {@code true} if it is unacceptable to perform the operation
     *            without applying the semantics of this control, or
     *            {@code false} if it can be ignored.
     * @return The new control.
     */
    public static RealAttributesOnlyRequestControl newControl(final boolean isCritical) {
        return isCritical ? CRITICAL_INSTANCE : NONCRITICAL_INSTANCE;
    }

    private final boolean isCritical;

    private RealAttributesOnlyRequestControl(final boolean isCritical) {
        this.isCritical = isCritical;
    }

    /** {@inheritDoc} */
    public String getOID() {
        return OID;
    }

    /** {@inheritDoc} */
    public ByteString getValue() {
        return null;
    }

    /** {@inheritDoc} */
    public boolean hasValue() {
        return false;
    }

    /** {@inheritDoc} */
    public boolean isCritical() {
        return isCritical;
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("RealAttributesOnlyRequestControl(oid=");
        builder.append(getOID());
        builder.append(", criticality=");
        builder.append(isCritical());
        builder.append(")");
        return builder.toString();
    }

}
