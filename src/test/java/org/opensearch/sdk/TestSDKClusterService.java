/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.sdk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Setting.Property;
import org.opensearch.common.settings.Settings;
import org.opensearch.extensions.DiscoveryExtensionNode;
import org.opensearch.sdk.SDKClusterService.SDKClusterSettings;
import org.opensearch.sdk.handlers.AcknowledgedResponseHandler;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.transport.TransportService;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

public class TestSDKClusterService extends OpenSearchTestCase {
    private ExtensionsRunner extensionsRunner;
    private SDKClusterService sdkClusterService;

    @Override
    @BeforeEach
    public void setUp() throws Exception {
        super.setUp();
        this.extensionsRunner = spy(new ExtensionsRunnerForTest());
        this.sdkClusterService = new SDKClusterService(extensionsRunner);
    }

    @Test
    public void testState() throws Exception {
        // Before initialization should throw exception
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> sdkClusterService.state());
        assertEquals("The Extensions Runner has not been initialized.", ex.getMessage());

        // After initialization should be successful
        when(extensionsRunner.isInitialized()).thenReturn(true);
        sdkClusterService.state();
        verify(extensionsRunner, times(1)).getSdkTransportService();

        ArgumentCaptor<TransportService> argumentCaptor = ArgumentCaptor.forClass(TransportService.class);
        assertNull(argumentCaptor.getValue());
    }

    @Test
    public void testLocalNode() {
        DiscoveryExtensionNode expectedLocalNode = extensionsRunner.getExtensionNode();
        DiscoveryExtensionNode localNode = sdkClusterService.localNode();
        assertEquals(expectedLocalNode, localNode);
    }

    @Test
    public void testGetClusterSettings() {
        assertInstanceOf(SDKClusterSettings.class, sdkClusterService.getClusterSettings());
    }

    @Test
    public void testUpdateSdkClusterSettings() {
        ExtensionsRunner mockRunner = mock(ExtensionsRunner.class);
        Extension mockExtension = mock(Extension.class);
        when(mockRunner.getExtension()).thenReturn(mockExtension);
        when(mockRunner.getEnvironmentSettings()).thenReturn(Settings.EMPTY);
        when(mockExtension.getSettings()).thenReturn(Collections.emptyList());
        SDKClusterService clusterService = new SDKClusterService(mockRunner);

        SDKClusterSettings sdkClusterSettings = clusterService.getClusterSettings();
        assertEquals(Property.NodeScope, sdkClusterSettings.getScope());

        String testKey = "test.setting";
        String testValue = "test.setting.value";
        Setting<String> testSetting = Setting.simpleString(testKey, testValue);
        when(mockExtension.getSettings()).thenReturn(List.of(testSetting));

        // Before settings set
        assertNull(sdkClusterSettings.get(testKey));

        assertDoesNotThrow(() -> clusterService.updateSdkClusterSettings());
        // After settings set
        assertNotNull(sdkClusterSettings.get(testKey));
        assertEquals(testSetting.toString(), sdkClusterSettings.get(testKey).toString());
    }

    @Test
    public void testAddSettingsUpdateConsumer() throws Exception {
        Setting<Boolean> boolSetting = Setting.boolSetting("test", false);
        Consumer<Boolean> boolConsumer = b -> {};

        TransportService mockTransportService = mock(TransportService.class);
        extensionsRunner.getSdkTransportService().setTransportService(mockTransportService);

        // Before initialization should store pending update but do nothing
        sdkClusterService.getClusterSettings().addSettingsUpdateConsumer(boolSetting, boolConsumer);
        verify(extensionsRunner.getSdkTransportService().getTransportService(), times(0)).sendRequest(
            any(),
            anyString(),
            any(),
            any(AcknowledgedResponseHandler.class)
        );

        // After initialization should be able to send pending updates
        extensionsRunner.setInitialized();
        sdkClusterService.getClusterSettings().sendPendingSettingsUpdateConsumers();
        verify(extensionsRunner.getSdkTransportService().getTransportService(), times(1)).sendRequest(
            any(),
            anyString(),
            any(),
            any(AcknowledgedResponseHandler.class)
        );

        // Once updates sent, map is empty, shouldn't send on retry (keep cumulative 1)
        sdkClusterService.getClusterSettings().sendPendingSettingsUpdateConsumers();
        verify(extensionsRunner.getSdkTransportService().getTransportService(), times(1)).sendRequest(
            any(),
            anyString(),
            any(),
            any(AcknowledgedResponseHandler.class)
        );

        // Sending a new update should send immediately (cumulative now 2)
        sdkClusterService.getClusterSettings().addSettingsUpdateConsumer(boolSetting, boolConsumer);
        verify(extensionsRunner.getSdkTransportService().getTransportService(), times(2)).sendRequest(
            any(),
            anyString(),
            any(),
            any(AcknowledgedResponseHandler.class)
        );

    }
}
