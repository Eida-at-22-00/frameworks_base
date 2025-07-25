/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.om;

import static android.content.Context.DEVICE_ID_DEFAULT;
import static android.content.om.OverlayConstraint.TYPE_DEVICE_ID;
import static android.content.om.OverlayConstraint.TYPE_DISPLAY_ID;
import static android.content.om.OverlayInfo.STATE_DISABLED;
import static android.content.om.OverlayInfo.STATE_ENABLED;
import static android.content.om.OverlayInfo.STATE_MISSING_TARGET;
import static android.os.OverlayablePolicy.CONFIG_SIGNATURE;
import static android.view.Display.DEFAULT_DISPLAY;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.testng.Assert.assertThrows;

import android.content.om.OverlayConstraint;
import android.content.om.OverlayIdentifier;
import android.content.om.OverlayInfo;
import android.content.pm.UserPackage;
import android.content.res.Flags;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@RunWith(JUnitParamsRunner.class)
public class OverlayManagerServiceImplTests extends OverlayManagerServiceImplTestsBase {

    private static final String OVERLAY = "com.test.overlay";
    private static final OverlayIdentifier IDENTIFIER = new OverlayIdentifier(OVERLAY);
    private static final String TARGET = "com.test.target";
    private static final int USER = 0;

    private static final String OVERLAY2 = OVERLAY + "2";
    private static final String TARGET2 = TARGET + "2";
    private static final OverlayIdentifier IDENTIFIER2 = new OverlayIdentifier(OVERLAY2);
    private static final int USER2 = USER + 1;

    private static final String OVERLAY3 = OVERLAY + "3";
    private static final OverlayIdentifier IDENTIFIER3 = new OverlayIdentifier(OVERLAY3);
    private static final int USER3 = USER2 + 1;

    private static final String CONFIG_SIGNATURE_REFERENCE_PKG = "com.test.ref";
    private static final String CERT_CONFIG_OK = "config_certificate_ok";
    private static final String CERT_CONFIG_NOK = "config_certificate_nok";

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Test
    public void testGetOverlayInfo() throws Exception {
        installAndAssert(overlay(OVERLAY, TARGET), USER,
                Set.of(UserPackage.of(USER, OVERLAY), UserPackage.of(USER, TARGET)));

        final OverlayManagerServiceImpl impl = getImpl();
        final OverlayInfo oi = impl.getOverlayInfo(IDENTIFIER, USER);
        assertNotNull(oi);
        assertEquals(oi.packageName, OVERLAY);
        assertEquals(oi.targetPackageName, TARGET);
        assertEquals(oi.userId, USER);
    }

    @Test
    public void testGetOverlayInfosForTarget() throws Exception {
        installAndAssert(overlay(OVERLAY, TARGET), USER,
                Set.of(UserPackage.of(USER, OVERLAY), UserPackage.of(USER, TARGET)));
        installAndAssert(overlay(OVERLAY2, TARGET), USER,
                Set.of(UserPackage.of(USER, OVERLAY2), UserPackage.of(USER, TARGET)));
        installAndAssert(overlay(OVERLAY3, TARGET), USER2,
                Set.of(UserPackage.of(USER2, OVERLAY3), UserPackage.of(USER2, TARGET)));

        final OverlayManagerServiceImpl impl = getImpl();
        final List<OverlayInfo> ois = impl.getOverlayInfosForTarget(TARGET, USER);
        assertEquals(ois.size(), 2);
        assertTrue(ois.contains(impl.getOverlayInfo(IDENTIFIER, USER)));
        assertTrue(ois.contains(impl.getOverlayInfo(IDENTIFIER2, USER)));

        final List<OverlayInfo> ois2 = impl.getOverlayInfosForTarget(TARGET, USER2);
        assertEquals(ois2.size(), 1);
        assertTrue(ois2.contains(impl.getOverlayInfo(IDENTIFIER3, USER2)));

        final List<OverlayInfo> ois3 = impl.getOverlayInfosForTarget(TARGET, USER3);
        assertNotNull(ois3);
        assertEquals(ois3.size(), 0);

        final List<OverlayInfo> ois4 = impl.getOverlayInfosForTarget("no.such.overlay", USER);
        assertNotNull(ois4);
        assertEquals(ois4.size(), 0);
    }

    @Test
    public void testGetOverlayInfosForUser() throws Exception {
        installAndAssert(target(TARGET), USER,
                Set.of(UserPackage.of(USER, TARGET)));
        installAndAssert(overlay(OVERLAY, TARGET), USER,
                Set.of(UserPackage.of(USER, OVERLAY), UserPackage.of(USER, TARGET)));
        installAndAssert(overlay(OVERLAY2, TARGET), USER,
                Set.of(UserPackage.of(USER, OVERLAY2), UserPackage.of(USER, TARGET)));
        installAndAssert(overlay(OVERLAY3, TARGET2), USER,
                Set.of(UserPackage.of(USER, OVERLAY3), UserPackage.of(USER, TARGET2)));

        final OverlayManagerServiceImpl impl = getImpl();
        final Map<String, List<OverlayInfo>> everything = impl.getOverlaysForUser(USER);
        assertEquals(everything.size(), 2);

        final List<OverlayInfo> ois = everything.get(TARGET);
        assertNotNull(ois);
        assertEquals(ois.size(), 2);
        assertTrue(ois.contains(impl.getOverlayInfo(IDENTIFIER, USER)));
        assertTrue(ois.contains(impl.getOverlayInfo(IDENTIFIER2, USER)));

        final List<OverlayInfo> ois2 = everything.get(TARGET2);
        assertNotNull(ois2);
        assertEquals(ois2.size(), 1);
        assertTrue(ois2.contains(impl.getOverlayInfo(IDENTIFIER3, USER)));

        final Map<String, List<OverlayInfo>> everything2 = impl.getOverlaysForUser(USER2);
        assertNotNull(everything2);
        assertEquals(everything2.size(), 0);
    }

    @Test
    public void testPriority() throws Exception {
        installAndAssert(overlay(OVERLAY, TARGET), USER,
                Set.of(UserPackage.of(USER, OVERLAY), UserPackage.of(USER, TARGET)));
        installAndAssert(overlay(OVERLAY2, TARGET), USER,
                Set.of(UserPackage.of(USER, OVERLAY2), UserPackage.of(USER, TARGET)));
        installAndAssert(overlay(OVERLAY3, TARGET), USER,
                Set.of(UserPackage.of(USER, OVERLAY3), UserPackage.of(USER, TARGET)));

        final OverlayManagerServiceImpl impl = getImpl();
        final OverlayInfo o1 = impl.getOverlayInfo(IDENTIFIER, USER);
        final OverlayInfo o2 = impl.getOverlayInfo(IDENTIFIER2, USER);
        final OverlayInfo o3 = impl.getOverlayInfo(IDENTIFIER3, USER);

        assertOverlayInfoForTarget(TARGET, USER, o1, o2, o3);

        assertEquals(impl.setLowestPriority(IDENTIFIER3, USER),
                Optional.of(UserPackage.of(USER, TARGET)));
        assertOverlayInfoForTarget(TARGET, USER, o3, o1, o2);

        assertEquals(impl.setHighestPriority(IDENTIFIER3, USER),
                Set.of(UserPackage.of(USER, TARGET)));
        assertOverlayInfoForTarget(TARGET, USER, o1, o2, o3);

        assertEquals(impl.setPriority(IDENTIFIER, IDENTIFIER2, USER),
                Optional.of(UserPackage.of(USER, TARGET)));
        assertOverlayInfoForTarget(TARGET, USER, o2, o1, o3);
    }

    @Test
    public void testOverlayInfoStateTransitions() throws Exception {
        final OverlayManagerServiceImpl impl = getImpl();
        assertNull(impl.getOverlayInfo(IDENTIFIER, USER));

        installAndAssert(overlay(OVERLAY, TARGET), USER,
                Set.of(UserPackage.of(USER, OVERLAY), UserPackage.of(USER, TARGET)));
        assertState(STATE_MISSING_TARGET, IDENTIFIER, USER);

        installAndAssert(target(TARGET), USER,
                Set.of(UserPackage.of(USER, TARGET)));
        assertState(STATE_DISABLED, IDENTIFIER, USER);

        assertEquals(impl.setEnabled(IDENTIFIER, true /* enable */, USER,
                        Collections.emptyList() /* constraints */),
                Set.of(UserPackage.of(USER, TARGET)));
        assertState(STATE_ENABLED, IDENTIFIER, USER);

        // target upgrades do not change the state of the overlay
        upgradeAndAssert(target(TARGET), USER,
                Set.of(UserPackage.of(USER, TARGET)),
                Set.of(UserPackage.of(USER, TARGET)));
        assertState(STATE_ENABLED, IDENTIFIER, USER);

        uninstallAndAssert(TARGET, USER,
                Set.of(UserPackage.of(USER, TARGET)));
        assertState(STATE_MISSING_TARGET, IDENTIFIER, USER);

        installAndAssert(target(TARGET), USER,
                Set.of(UserPackage.of(USER, TARGET)));
        assertState(STATE_ENABLED, IDENTIFIER, USER);
    }

    @Test
    public void testOnOverlayPackageUpgraded() throws Exception {
        installAndAssert(target(TARGET), USER,
                Set.of(UserPackage.of(USER, TARGET)));
        installAndAssert(overlay(OVERLAY, TARGET), USER,
                Set.of(UserPackage.of(USER, OVERLAY), UserPackage.of(USER, TARGET)));
        upgradeAndAssert(overlay(OVERLAY, TARGET), USER,
                Set.of(UserPackage.of(USER, TARGET)),
                Set.of(UserPackage.of(USER, TARGET)));

        // upgrade to a version where the overlay has changed its target
        upgradeAndAssert(overlay(OVERLAY, TARGET2), USER,
                Set.of(UserPackage.of(USER, TARGET)),
                Set.of(UserPackage.of(USER, TARGET),
                        UserPackage.of(USER, TARGET2)));
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_RRO_CONSTRAINTS)
    public void testSetEnabledAtVariousConditions() throws Exception {
        testSetEnabledAtVariousConditions(Collections.emptyList());
    }

    @Test
    @Parameters(method = "getConstraintLists")
    @RequiresFlagsEnabled(Flags.FLAG_RRO_CONSTRAINTS)
    public void testSetEnabledAtVariousConditionsWithConstraints(
            List<OverlayConstraint> constraints) throws Exception {
        testSetEnabledAtVariousConditions(constraints);
    }

    @Test
    public void testConfigSignaturePolicyOk() throws Exception {
        setConfigSignaturePackageName(CONFIG_SIGNATURE_REFERENCE_PKG);
        reinitializeImpl();

        addPackage(target(CONFIG_SIGNATURE_REFERENCE_PKG).setCertificate(CERT_CONFIG_OK), USER);
        installAndAssert(target(TARGET), USER,
                Set.of(UserPackage.of(USER, TARGET)));
        installAndAssert(overlay(OVERLAY, TARGET).setCertificate(CERT_CONFIG_OK), USER,
                Set.of(UserPackage.of(USER, OVERLAY), UserPackage.of(USER, TARGET)));

        final FakeIdmapDaemon idmapd = getIdmapd();
        final FakeDeviceState state = getState();
        final String overlayPath = state.select(OVERLAY, USER).apkPath;
        assertTrue(idmapd.idmapExists(overlayPath, USER));

        final FakeIdmapDaemon.IdmapHeader idmap = idmapd.getIdmap(overlayPath);
        assertEquals(CONFIG_SIGNATURE, CONFIG_SIGNATURE & idmap.policies);
    }

    @Test
    public void testConfigSignaturePolicyCertNok() throws Exception {
        setConfigSignaturePackageName(CONFIG_SIGNATURE_REFERENCE_PKG);
        reinitializeImpl();

        addPackage(target(CONFIG_SIGNATURE_REFERENCE_PKG).setCertificate(CERT_CONFIG_OK), USER);
        installAndAssert(target(TARGET), USER,
                Set.of(UserPackage.of(USER, TARGET)));
        installAndAssert(overlay(OVERLAY, TARGET).setCertificate(CERT_CONFIG_NOK), USER,
                Set.of(UserPackage.of(USER, OVERLAY), UserPackage.of(USER, TARGET)));

        final FakeIdmapDaemon idmapd = getIdmapd();
        final FakeDeviceState state = getState();
        final String overlayPath = state.select(OVERLAY, USER).apkPath;
        assertTrue(idmapd.idmapExists(overlayPath, USER));

        final FakeIdmapDaemon.IdmapHeader idmap = idmapd.getIdmap(overlayPath);
        assertEquals(0, CONFIG_SIGNATURE & idmap.policies);
    }

    @Test
    public void testConfigSignaturePolicyNoConfig() throws Exception {
        addPackage(target(CONFIG_SIGNATURE_REFERENCE_PKG).setCertificate(CERT_CONFIG_OK), USER);
        installAndAssert(target(TARGET), USER,
                Set.of(UserPackage.of(USER, TARGET)));
        installAndAssert(overlay(OVERLAY, TARGET).setCertificate(CERT_CONFIG_NOK), USER,
                Set.of(UserPackage.of(USER, OVERLAY), UserPackage.of(USER, TARGET)));

        final FakeIdmapDaemon idmapd = getIdmapd();
        final FakeDeviceState state = getState();
        final String overlayPath = state.select(OVERLAY, USER).apkPath;
        assertTrue(idmapd.idmapExists(overlayPath, USER));

        final FakeIdmapDaemon.IdmapHeader idmap = idmapd.getIdmap(overlayPath);
        assertEquals(0, CONFIG_SIGNATURE & idmap.policies);
    }

    @Test
    public void testConfigSignaturePolicyNoRefPkg() throws Exception {
        installAndAssert(target(TARGET), USER,
                Set.of(UserPackage.of(USER, TARGET)));
        installAndAssert(overlay(OVERLAY, TARGET).setCertificate(CERT_CONFIG_NOK), USER,
                Set.of(UserPackage.of(USER, OVERLAY), UserPackage.of(USER, TARGET)));

        final FakeIdmapDaemon idmapd = getIdmapd();
        final FakeDeviceState state = getState();
        final String overlayPath = state.select(OVERLAY, USER).apkPath;
        assertTrue(idmapd.idmapExists(overlayPath, USER));

        final FakeIdmapDaemon.IdmapHeader idmap = idmapd.getIdmap(overlayPath);
        assertEquals(0, CONFIG_SIGNATURE & idmap.policies);
    }

    @Test
    public void testConfigSignaturePolicyRefPkgNotSystem() throws Exception {
        setConfigSignaturePackageName(CONFIG_SIGNATURE_REFERENCE_PKG);
        reinitializeImpl();

        addPackage(app(CONFIG_SIGNATURE_REFERENCE_PKG).setCertificate(CERT_CONFIG_OK), USER);
        installAndAssert(target(TARGET), USER,
                Set.of(UserPackage.of(USER, TARGET)));
        installAndAssert(overlay(OVERLAY, TARGET).setCertificate(CERT_CONFIG_NOK), USER,
                Set.of(UserPackage.of(USER, OVERLAY), UserPackage.of(USER, TARGET)));

        final FakeIdmapDaemon idmapd = getIdmapd();
        final FakeDeviceState state = getState();
        String overlayPath = state.select(OVERLAY, USER).apkPath;
        assertTrue(idmapd.idmapExists(overlayPath, USER));

        FakeIdmapDaemon.IdmapHeader idmap = idmapd.getIdmap(overlayPath);
        assertEquals(0, CONFIG_SIGNATURE & idmap.policies);
    }

    @Test
    public void testOnTargetSystemPackageUninstall() throws Exception {
        installAndAssert(target(TARGET), USER,
                Set.of(UserPackage.of(USER, TARGET)));
        installAndAssert(overlay(OVERLAY, TARGET), USER,
                Set.of(UserPackage.of(USER, OVERLAY), UserPackage.of(USER, TARGET)));
        upgradeAndAssert(target(TARGET), USER,
                Set.of(UserPackage.of(USER, TARGET)),
                Set.of(UserPackage.of(USER, TARGET)));

        downgradeAndAssert(target(TARGET), USER,
                Set.of(UserPackage.of(USER, TARGET)),
                Set.of(UserPackage.of(USER, TARGET)));
    }

    private void testSetEnabledAtVariousConditions(final List<OverlayConstraint> constraints)
            throws Exception {
        final OverlayManagerServiceImpl impl = getImpl();
        assertThrows(OverlayManagerServiceImpl.OperationFailedException.class,
                () -> impl.setEnabled(IDENTIFIER, true /* enable */, USER, constraints));

        // request succeeded, and there was a change that needs to be
        // propagated to the rest of the system
        installAndAssert(target(TARGET), USER,
                Set.of(UserPackage.of(USER, TARGET)));
        installAndAssert(overlay(OVERLAY, TARGET), USER,
                Set.of(UserPackage.of(USER, OVERLAY), UserPackage.of(USER, TARGET)));
        assertEquals(Set.of(UserPackage.of(USER, TARGET)),
                impl.setEnabled(IDENTIFIER, true /* enable */, USER, constraints));

        // request succeeded, but nothing changed
        assertEquals(Set.of(), impl.setEnabled(IDENTIFIER, true /* enable */, USER, constraints));
    }

    private static List<OverlayConstraint>[] getConstraintLists() {
        return new List[]{
                Collections.emptyList(),
                List.of(new OverlayConstraint(TYPE_DISPLAY_ID, DEFAULT_DISPLAY)),
                List.of(new OverlayConstraint(TYPE_DEVICE_ID, DEVICE_ID_DEFAULT)),
                List.of(new OverlayConstraint(TYPE_DEVICE_ID, DEVICE_ID_DEFAULT),
                        new OverlayConstraint(TYPE_DEVICE_ID, DEVICE_ID_DEFAULT))
        };
    }
}
