package com.jamierf.rsc.client.loader;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import javassist.*;

import javax.swing.*;
import java.applet.Applet;
import java.applet.AppletStub;
import java.awt.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.URL;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarInputStream;
import java.util.zip.ZipEntry;

public class GameClient extends JPanel implements Runnable {

    private static final Dimension CLIENT_RESOLUTION = new Dimension(512, 345);

    private static URL buildServerURL(String serverHost) throws MalformedURLException {
        return new URL("http", serverHost, "/");
    }

    private static ImmutableMap<String, String> buildParameterMap() {
        return ImmutableMap.<String, String>builder()
                .put("nodeid", "0")
                .put("modewhere", "0")
                .put("modewhat", "0")
                .put("servertype", "1")
                .put("advertsuppressed", "0")
                .put("objecttag", "0")
                .put("js", "1")
                .put("affid", "0")
                .put("settings", "wwGlrZHF5gKN6D3mDdihco3oPeYN2KFybL9hUUFqOvk")
                .put("country", "0")
                .put("haveie6", "0")
                .build();
    }

    // TODO: Download the resources and cache locally. verify SHA1 against expected

    private static File getTempDirectory() {
        return Files.createTempDir();
    }

    private static Applet loadClientApplet(URL resourceURL, URL serverURL, RSAPublicKey key) throws GameClientModificationException {
        final File tempDir = GameClient.getTempDirectory();

        final Class<Applet> clazz = GameClient.loadClientAppletClass(resourceURL, key, tempDir);

        final AppletStub stub = new MockAppletStub(resourceURL, serverURL, GameClient.buildParameterMap());

        try {
            final Applet applet = clazz.getConstructor(GameClientCallback.class).newInstance(stub);
            applet.setStub(stub);

            return applet;
        }
        catch (ReflectiveOperationException e) {
            throw new GameClientModificationException("Unable to instanciate client", e);
        }
    }

    private static Class<Applet> loadClientAppletClass(URL resourceURL, RSAPublicKey key, File tempDir) throws GameClientModificationException {
        try {
            // Download the class files to our temp directory
            GameClient.fetchClasses(resourceURL, tempDir);

            final DirectoryClassLoader loader = new DirectoryClassLoader(tempDir, ClassLoader.getSystemClassLoader());

            // Create a class pool with our classes and the game client in it
            final ClassPool pool = ClassPool.getDefault();
            pool.appendClassPath(new ClassClassPath(GameClientCallback.class));
            pool.appendClassPath(loader);
            pool.appendSystemPath();

            // Load the game client class
            final CtClass client = pool.get("client");

            // Load the game client callback class and add it as a field to the client
            final CtClass callback = pool.get(GameClientCallback.class.getName());
            client.addField(new CtField(callback, "callback", client));

            // Create a new constructor for the game client, which sets the callback
            final CtConstructor constructor = new CtConstructor(new CtClass[]{callback}, client);
            constructor.setBody("{ this(); this.callback = $1; }");
            client.addConstructor(constructor);

            // Find the connect method based on its parameters and return type and inject the callback
            final CtMethod connect = GameClient.findConnectMethod(pool, client);
            connect.insertBefore("callback.beforeConnect();");
            connect.insertAfter("callback.afterConnect();");

            // Compile and load the client class
            final Class<Applet> clazz = client.toClass(loader, GameClient.class.getProtectionDomain());

            // Find all big integers from the game client
            final ImmutableList<Field> bigIntegers = GameClient.findEncryptionKeys(loader, loader.getClassNames());
            if (bigIntegers.size() != 2)
                throw new GameClientModificationException("Unable to find encryption keys in client");

            // Update the game clients encryption keys to ours
            GameClient.setEncryptionKey(bigIntegers.get(0), key.getModulus());
            GameClient.setEncryptionKey(bigIntegers.get(1), key.getPublicExponent());

            return clazz;
        }
        catch (NotFoundException e) {
            throw new GameClientModificationException("Unable to find required class", e);
        }
        catch (CannotCompileException e) {
            throw new GameClientModificationException("Unable to compile modified class", e);
        }
        catch (IllegalAccessException e) {
            throw new GameClientModificationException("Unable to modify loaded class", e);
        }
        catch (MalformedURLException e) {
            throw new GameClientModificationException("Unable to find local classes", e);
        }
        catch (ClassNotFoundException e) {
            throw new GameClientModificationException("Unable to find classes", e);
        }
        catch (IOException e) {
            throw new GameClientModificationException("Unable to download game client jar", e);
        }
    }

    private static void fetchClasses(URL resourceURL, File destination) throws IOException {
        final JarInputStream in = new JarInputStream(new URL(resourceURL, "rsclassic.jar").openStream(), true);

        for (ZipEntry entry; (entry = in.getNextEntry()) != null;) {
            // Skip non class files
            final String name = entry.getName();
            if (!name.endsWith(".class"))
                continue;

            final FileOutputStream out = new FileOutputStream(new File(destination, entry.getName()));
            try {
                ByteStreams.copy(in, out);
            }
            finally {
                out.close();
            }
        }
    }

    private static void setEncryptionKey(Field field, BigInteger value) throws IllegalAccessException {
        final boolean accessible = field.isAccessible();

        // Make the field accessible
        field.setAccessible(true);

        System.out.println("Replacing encryption key " + field.get(null) + " with " + value);

        field.set(null, value);

        // Reset it to how it was before
        field.setAccessible(accessible);
    }

    private static ImmutableList<Field> findEncryptionKeys(ClassLoader loader, Iterable<String> classNames) throws ClassNotFoundException {
        final List<Field> fields = Lists.newArrayList();

        for (String className : classNames) {
            try {
                final Class<?> clazz = loader.loadClass(className);
                for (Field field : clazz.getDeclaredFields()) {
                    if (!BigInteger.class.equals(field.getType()))
                        continue;

                    fields.add(field);
                }
            }
            catch (NoClassDefFoundError e) {
                System.err.println("Unable to load class '" + className + "': Unable to load dependency " + e.getMessage());
            }
        }

        // Sort the big integers based on their bit length
        Collections.sort(fields, new Comparator<Field>() {
            @Override
            public int compare(Field o1, Field o2) {
                try {
                    final BigInteger v1 = (BigInteger) o1.get(null);
                    final BigInteger v2 = (BigInteger) o2.get(null);
                    return Integer.compare(v1.bitLength(), v2.bitLength());
                }
                catch (IllegalAccessException e) {
                    return 0;
                }
            }
        });

        return ImmutableList.copyOf(fields);
    }

    private static CtMethod findConnectMethod(ClassPool pool, CtClass client) throws NotFoundException, CannotCompileException {
        final CtClass returnType = pool.get(Socket.class.getName());
        final CtClass[] parameterTypes = { CtClass.byteType, pool.get(String.class.getName()), CtClass.intType };

        for (CtMethod method : client.getDeclaredMethods()) {
            if (!returnType.equals(method.getReturnType()))
                continue;

            if (!Arrays.equals(parameterTypes, method.getParameterTypes()))
                continue;

            return method;
        }

        return null;
    }

    private final Applet client;

    public GameClient(URL resourceURL, String serverHost, RSAPublicKey key) throws Exception {
        this (resourceURL, GameClient.buildServerURL(serverHost), key);
    }

    public GameClient(URL resourceURL, URL serverURL, RSAPublicKey key) throws Exception {
        super(new BorderLayout());

        client = GameClient.loadClientApplet(resourceURL, serverURL, key);

        super.setPreferredSize(CLIENT_RESOLUTION);
        super.add(client);
    }

    @Override
    public void run() {
        client.init();
        client.start();
    }
}
