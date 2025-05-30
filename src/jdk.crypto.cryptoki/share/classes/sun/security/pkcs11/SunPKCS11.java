/*
 * Copyright (c) 2003, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sun.security.pkcs11;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;
import java.security.*;
import java.security.interfaces.*;

import javax.crypto.KDFParameters;
import javax.crypto.interfaces.*;

import javax.security.auth.Subject;
import javax.security.auth.login.LoginException;
import javax.security.auth.login.FailedLoginException;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.PasswordCallback;

import com.sun.crypto.provider.ChaCha20Poly1305Parameters;

import com.sun.crypto.provider.DHParameters;
import jdk.internal.misc.InnocuousThread;
import sun.security.rsa.PSSParameters;
import sun.security.util.Debug;
import sun.security.util.ResourcesMgr;
import static sun.security.util.SecurityConstants.PROVIDER_VER;
import static sun.security.util.SecurityProviderConstants.getAliases;

import sun.security.pkcs11.Secmod.*;

import sun.security.pkcs11.wrapper.*;
import static sun.security.pkcs11.wrapper.PKCS11Constants.*;
import static sun.security.pkcs11.wrapper.PKCS11Exception.RV.*;

/**
 * PKCS#11 provider main class.
 *
 * @author  Andreas Sterbenz
 * @since   1.5
 */
public final class SunPKCS11 extends AuthProvider {

    @Serial
    private static final long serialVersionUID = -1354835039035306505L;

    static final Debug debug = Debug.getInstance("sunpkcs11");
    // the PKCS11 object through which we make the native calls
    @SuppressWarnings("serial") // Type of field is not Serializable;
                                // see writeReplace
    final PKCS11 p11;

    // configuration information
    @SuppressWarnings("serial") // Type of field is not Serializable
    final Config config;

    // id of the PKCS#11 slot we are using
    final long slotID;

    @SuppressWarnings("serial") // Type of field is not Serializable
    private CallbackHandler pHandler;
    @SuppressWarnings("serial") // Type of field is not Serializable
    private final Object LOCK_HANDLER = new Object();

    final boolean removable;

    @SuppressWarnings("serial") // Type of field is not Serializable
    final Secmod.Module nssModule;

    final boolean nssUseSecmodTrust;

    private volatile Token token;

    @SuppressWarnings("serial") // Type of field is not Serializable
    private TokenPoller poller;

    static NativeResourceCleaner cleaner;

    Token getToken() {
        return token;
    }

    public SunPKCS11() {
        super("SunPKCS11", PROVIDER_VER,
            "Unconfigured and unusable PKCS11 provider");
        p11 = null;
        config = null;
        slotID = 0;
        pHandler = null;
        removable = false;
        nssModule = null;
        nssUseSecmodTrust = false;
        token = null;
        poller = null;
    }

    @Override
    public Provider configure(String configArg) throws InvalidParameterException {
        final String newConfigName = checkNull(configArg);
        try {
            return new SunPKCS11(new Config(newConfigName));
        } catch (IOException ioe) {
            throw new InvalidParameterException("Error configuring SunPKCS11 provider", ioe);
        }
    }

    @Override
    public boolean isConfigured() {
        return (config != null);
    }

    private static <T> T checkNull(T obj) {
        if (obj == null) {
            throw new NullPointerException();
        }
        return obj;
    }

    // Used by Secmod
    SunPKCS11(Config c) {
        super("SunPKCS11-" + c.getName(), PROVIDER_VER, c.getDescription());
        this.config = c;

        if (debug != null) {
            debug.println("SunPKCS11 loading " + config.getFileName());
        }

        String library = config.getLibrary();
        String functionList = config.getFunctionList();
        long slotID = config.getSlotID();
        int slotListIndex = config.getSlotListIndex();

        boolean useSecmod = config.getNssUseSecmod();
        boolean nssUseSecmodTrust = config.getNssUseSecmodTrust();
        Secmod.Module nssModule = null;

        //
        // Initialization via Secmod. The way this works is as follows:
        // SunPKCS11 is either in normal mode or in NSS Secmod mode.
        // Secmod is activated by specifying one or more of the following
        // options in the config file:
        // nssUseSecmod, nssSecmodDirectory, nssLibrary, nssModule
        //
        // XXX add more explanation here
        //
        // If we are in Secmod mode and configured to use either the
        // nssKeyStore or the nssTrustAnchors module, we automatically
        // switch to using the NSS trust attributes for trusted certs
        // (KeyStore).
        //
        if (useSecmod) {
            // note: Config ensures library/slot/slotListIndex not specified
            // in secmod mode.
            Secmod secmod = Secmod.getInstance();
            DbMode nssDbMode = config.getNssDbMode();
            try {
                String nssLibraryDirectory = config.getNssLibraryDirectory();
                String nssSecmodDirectory = config.getNssSecmodDirectory();
                boolean nssOptimizeSpace = config.getNssOptimizeSpace();

                if (secmod.isInitialized()) {
                    if (nssSecmodDirectory != null) {
                        String s = secmod.getConfigDir();
                        if ((s != null) &&
                                (!s.equals(nssSecmodDirectory))) {
                            throw new ProviderException("Secmod directory "
                                + nssSecmodDirectory
                                + " invalid, NSS already initialized with "
                                + s);
                        }
                    }
                    if (nssLibraryDirectory != null) {
                        String s = secmod.getLibDir();
                        if ((s != null) &&
                                (!s.equals(nssLibraryDirectory))) {
                            throw new ProviderException("NSS library directory "
                                + nssLibraryDirectory
                                + " invalid, NSS already initialized with "
                                + s);
                        }
                    }
                } else {
                    if (nssDbMode != DbMode.NO_DB) {
                        if (nssSecmodDirectory == null) {
                            throw new ProviderException(
                                "Secmod not initialized and "
                                 + "nssSecmodDirectory not specified");
                        }
                    } else {
                        if (nssSecmodDirectory != null) {
                            throw new ProviderException(
                                "nssSecmodDirectory must not be "
                                + "specified in noDb mode");
                        }
                    }
                    secmod.initialize(nssDbMode, nssSecmodDirectory,
                        nssLibraryDirectory, nssOptimizeSpace);
                }
            } catch (IOException e) {
                // XXX which exception to throw
                throw new ProviderException("Could not initialize NSS", e);
            }
            List<Secmod.Module> modules = secmod.getModules();
            if (config.getShowInfo()) {
                System.out.println("NSS modules: " + modules);
            }

            String moduleName = config.getNssModule();
            if (moduleName == null) {
                nssModule = secmod.getModule(ModuleType.FIPS);
                if (nssModule != null) {
                    moduleName = "fips";
                } else {
                    moduleName = (nssDbMode == DbMode.NO_DB) ?
                        "crypto" : "keystore";
                }
            }
            if (moduleName.equals("fips")) {
                nssModule = secmod.getModule(ModuleType.FIPS);
                nssUseSecmodTrust = true;
                functionList = "FC_GetFunctionList";
            } else if (moduleName.equals("keystore")) {
                nssModule = secmod.getModule(ModuleType.KEYSTORE);
                nssUseSecmodTrust = true;
            } else if (moduleName.equals("crypto")) {
                nssModule = secmod.getModule(ModuleType.CRYPTO);
            } else if (moduleName.equals("trustanchors")) {
                // XXX should the option be called trustanchor or trustanchors??
                nssModule = secmod.getModule(ModuleType.TRUSTANCHOR);
                nssUseSecmodTrust = true;
            } else if (moduleName.startsWith("external-")) {
                int moduleIndex;
                try {
                    moduleIndex = Integer.parseInt
                            (moduleName.substring("external-".length()));
                } catch (NumberFormatException e) {
                    moduleIndex = -1;
                }
                if (moduleIndex < 1) {
                    throw new ProviderException
                            ("Invalid external module: " + moduleName);
                }
                int k = 0;
                for (Secmod.Module module : modules) {
                    if (module.getType() == ModuleType.EXTERNAL) {
                        if (++k == moduleIndex) {
                            nssModule = module;
                            break;
                        }
                    }
                }
                if (nssModule == null) {
                    throw new ProviderException("Invalid module " + moduleName
                        + ": only " + k + " external NSS modules available");
                }
            } else {
                throw new ProviderException(
                    "Unknown NSS module: " + moduleName);
            }
            if (nssModule == null) {
                throw new ProviderException(
                    "NSS module not available: " + moduleName);
            }
            if (nssModule.hasInitializedProvider()) {
                throw new ProviderException("Secmod module already configured");
            }
            library = nssModule.libraryName;
            slotListIndex = nssModule.slot;
        }
        this.nssUseSecmodTrust = nssUseSecmodTrust;
        this.nssModule = nssModule;

        File libraryFile = new File(library);
        // if the filename is a simple filename without path
        // (e.g. "libpkcs11.so"), it may refer to a library somewhere on the
        // OS library search path. Omit the test for file existence as that
        // only looks in the current directory.
        if (!libraryFile.getName().equals(library)) {
            if (!new File(library).isFile()) {
                String msg = "Library " + library + " does not exist";
                if (config.getHandleStartupErrors() == Config.ERR_HALT) {
                    throw new ProviderException(msg);
                } else {
                    throw new UnsupportedOperationException(msg);
                }
            }
        }

        try {
            if (debug != null) {
                debug.println("Initializing PKCS#11 library " + library);
            }
            CK_C_INITIALIZE_ARGS initArgs = new CK_C_INITIALIZE_ARGS();
            String nssArgs = config.getNssArgs();
            if (nssArgs != null) {
                initArgs.pReserved = nssArgs;
            }
            // request multithreaded access first
            initArgs.flags = CKF_OS_LOCKING_OK;
            PKCS11 tmpPKCS11;
            try {
                tmpPKCS11 = PKCS11.getInstance(library, functionList, initArgs,
                    config.getOmitInitialize());
            } catch (PKCS11Exception e) {
                if (debug != null) {
                    debug.println("Multi-threaded initialization failed: " + e);
                }
                if (!config.getAllowSingleThreadedModules()) {
                    throw e;
                }
                // fall back to single threaded access
                if (nssArgs == null) {
                    // if possible, use null initArgs for better compatibility
                    initArgs = null;
                } else {
                    initArgs.flags = 0;
                }
                tmpPKCS11 = PKCS11.getInstance(library, functionList, initArgs,
                    config.getOmitInitialize());
            }
            p11 = tmpPKCS11;

            if (p11.getVersion().major < 2) {
                throw new ProviderException("Only PKCS#11 v2.0 and later "
                + "supported, library version is v" + p11.getVersion());
            }
            boolean showInfo = config.getShowInfo();
            if (showInfo) {
                CK_INFO p11Info = p11.C_GetInfo();
                System.out.println("Information for provider " + getName());
                System.out.println("Library info:");
                System.out.println(p11Info);
            }

            if ((slotID < 0) || showInfo) {
                long[] slots = p11.C_GetSlotList(false);
                if (showInfo) {
                    System.out.println("All slots: " + toString(slots));
                    System.out.println("Slots with tokens: " +
                            toString(p11.C_GetSlotList(true)));
                }
                if (slotID < 0) {
                    if ((slotListIndex < 0)
                            || (slotListIndex >= slots.length)) {
                        throw new ProviderException("slotListIndex is "
                            + slotListIndex
                            + " but token only has " + slots.length + " slots");
                    }
                    slotID = slots[slotListIndex];
                }
            }
            this.slotID = slotID;
            CK_SLOT_INFO slotInfo = p11.C_GetSlotInfo(slotID);
            removable = (slotInfo.flags & CKF_REMOVABLE_DEVICE) != 0;
            initToken(slotInfo);
            if (nssModule != null) {
                nssModule.setProvider(this);
            }
        } catch (Exception e) {
            if (config.getHandleStartupErrors() == Config.ERR_IGNORE_ALL) {
                throw new UnsupportedOperationException
                        ("Initialization failed", e);
            } else {
                throw new ProviderException
                        ("Initialization failed", e);
            }
        }
    }

    private static String toString(long[] longs) {
        if (longs.length == 0) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(longs[0]);
        for (int i = 1; i < longs.length; i++) {
            sb.append(", ");
            sb.append(longs[i]);
        }
        return sb.toString();
    }

    public boolean equals(Object obj) {
        return this == obj;
    }

    public int hashCode() {
        return System.identityHashCode(this);
    }

    private static final class Descriptor {
        final String type;
        final String algorithm;
        final String className;
        final List<String> aliases;
        final int[] mechanisms;
        final int[] requiredMechs;

        private Descriptor(String type, String algorithm, String className,
                           List<String> aliases, int[] mechanisms) {
            this(type, algorithm, className, aliases, mechanisms, null);
        }
        // mechanisms is a list of possible mechanisms that implement the
        // algorithm, at least one of them must be available. requiredMechs
        // is a list of auxiliary mechanisms, all of them must be available
        private Descriptor(String type, String algorithm, String className,
                List<String> aliases, int[] mechanisms, int[] requiredMechs) {
            this.type = type;
            this.algorithm = algorithm;
            this.className = className;
            this.aliases = aliases;
            this.mechanisms = mechanisms;
            this.requiredMechs = requiredMechs;
        }
        private P11Service service(Token token, int mechanism) {
            return new P11Service
                (token, type, algorithm, className, aliases, mechanism);
        }
        public String toString() {
            return type + "." + algorithm;
        }
    }

    // Map from mechanism to List of Descriptors that should be
    // registered if the mechanism is supported
    private static final Map<Integer,List<Descriptor>> descriptors =
        new HashMap<Integer,List<Descriptor>>();

    private static int[] m(long m1) {
        return new int[] {(int)m1};
    }

    private static int[] m(long m1, long m2) {
        return new int[] {(int)m1, (int)m2};
    }

    private static int[] m(long m1, long m2, long m3) {
        return new int[] {(int)m1, (int)m2, (int)m3};
    }

    private static int[] m(long m1, long m2, long m3, long m4) {
        return new int[] {(int)m1, (int)m2, (int)m3, (int)m4};
    }

    private static void d(String type, String algorithm, String className,
            int[] m) {
        register(new Descriptor(type, algorithm, className, null, m));
    }

    private static void d(String type, String algorithm, String className,
            List<String> aliases, int[] m) {
        register(new Descriptor(type, algorithm, className, aliases, m));
    }

    private static void d(String type, String algorithm, String className,
            int[] m, int[] requiredMechs) {
        register(new Descriptor(type, algorithm, className, null, m,
                requiredMechs));
    }

    private static void dA(String type, String algorithm, String className,
            int[] m) {
        register(new Descriptor(type, algorithm, className,
                getAliases(algorithm), m));
    }

    private static void dA(String type, String algorithm, String className,
            int[] m, int[] requiredMechs) {
        register(new Descriptor(type, algorithm, className,
                getAliases(algorithm), m, requiredMechs));
    }

    private static void register(Descriptor d) {
        for (int i = 0; i < d.mechanisms.length; i++) {
            int m = d.mechanisms[i];
            Integer key = Integer.valueOf(m);
            List<Descriptor> list = descriptors.computeIfAbsent(key, k -> new ArrayList<>());
            list.add(d);
        }
    }

    private static final String MD  = "MessageDigest";

    private static final String SIG = "Signature";

    private static final String KPG = "KeyPairGenerator";

    private static final String KG  = "KeyGenerator";

    private static final String AGP = "AlgorithmParameters";

    private static final String KF  = "KeyFactory";

    private static final String SKF = "SecretKeyFactory";

    private static final String CIP = "Cipher";

    private static final String MAC = "Mac";

    private static final String KA  = "KeyAgreement";

    private static final String KS  = "KeyStore";

    private static final String SR  = "SecureRandom";

    private static final String KDF  = "KDF";

    static {
        // names of all the implementation classes
        // use local variables, only used here
        String P11Digest           = "sun.security.pkcs11.P11Digest";
        String P11Mac              = "sun.security.pkcs11.P11Mac";
        String P11KeyPairGenerator = "sun.security.pkcs11.P11KeyPairGenerator";
        String P11KeyGenerator     = "sun.security.pkcs11.P11KeyGenerator";
        String P11RSAKeyFactory    = "sun.security.pkcs11.P11RSAKeyFactory";
        String P11DSAKeyFactory    = "sun.security.pkcs11.P11DSAKeyFactory";
        String P11DHKeyFactory     = "sun.security.pkcs11.P11DHKeyFactory";
        String P11ECKeyFactory     = "sun.security.pkcs11.P11ECKeyFactory";
        String P11KeyAgreement     = "sun.security.pkcs11.P11KeyAgreement";
        String P11SecretKeyFactory = "sun.security.pkcs11.P11SecretKeyFactory";
        String P11Cipher           = "sun.security.pkcs11.P11Cipher";
        String P11KeyWrapCipher    = "sun.security.pkcs11.P11KeyWrapCipher";
        String P11RSACipher        = "sun.security.pkcs11.P11RSACipher";
        String P11AEADCipher       = "sun.security.pkcs11.P11AEADCipher";
        String P11PBECipher        = "sun.security.pkcs11.P11PBECipher";
        String P11Signature        = "sun.security.pkcs11.P11Signature";
        String P11PSSSignature     = "sun.security.pkcs11.P11PSSSignature";
        String P11HKDF             = "sun.security.pkcs11.P11HKDF";

        // XXX register all aliases

        d(MD, "MD2",            P11Digest,
                m(CKM_MD2));
        d(MD, "MD5",            P11Digest,
                m(CKM_MD5));
        dA(MD, "SHA-1",           P11Digest,
                m(CKM_SHA_1));

        dA(MD, "SHA-224",        P11Digest,
                m(CKM_SHA224));
        dA(MD, "SHA-256",        P11Digest,
                m(CKM_SHA256));
        dA(MD, "SHA-384",        P11Digest,
                m(CKM_SHA384));
        dA(MD, "SHA-512",        P11Digest,
                m(CKM_SHA512));
        dA(MD, "SHA-512/224",        P11Digest,
                m(CKM_SHA512_224));
        dA(MD, "SHA-512/256",        P11Digest,
                m(CKM_SHA512_256));
        dA(MD, "SHA3-224",        P11Digest,
                m(CKM_SHA3_224));
        dA(MD, "SHA3-256",        P11Digest,
                m(CKM_SHA3_256));
        dA(MD, "SHA3-384",        P11Digest,
                m(CKM_SHA3_384));
        dA(MD, "SHA3-512",        P11Digest,
                m(CKM_SHA3_512));

        d(MAC, "HmacMD5",       P11Mac,
                m(CKM_MD5_HMAC));
        dA(MAC, "HmacSHA1",      P11Mac,
                m(CKM_SHA_1_HMAC));
        dA(MAC, "HmacSHA224",    P11Mac,
                m(CKM_SHA224_HMAC));
        dA(MAC, "HmacSHA256",    P11Mac,
                m(CKM_SHA256_HMAC));
        dA(MAC, "HmacSHA384",    P11Mac,
                m(CKM_SHA384_HMAC));
        dA(MAC, "HmacSHA512",    P11Mac,
                m(CKM_SHA512_HMAC));
        dA(MAC, "HmacSHA512/224",    P11Mac,
                m(CKM_SHA512_224_HMAC));
        dA(MAC, "HmacSHA512/256",    P11Mac,
                m(CKM_SHA512_256_HMAC));
        dA(MAC, "HmacSHA3-224",    P11Mac,
                m(CKM_SHA3_224_HMAC));
        dA(MAC, "HmacSHA3-256",    P11Mac,
                m(CKM_SHA3_256_HMAC));
        dA(MAC, "HmacSHA3-384",    P11Mac,
                m(CKM_SHA3_384_HMAC));
        dA(MAC, "HmacSHA3-512",    P11Mac,
                m(CKM_SHA3_512_HMAC));
        d(MAC, "SslMacMD5",     P11Mac,
                m(CKM_SSL3_MD5_MAC));
        d(MAC, "SslMacSHA1",    P11Mac,
                m(CKM_SSL3_SHA1_MAC));

        /*
        * PBA HMacs
        *
        * KeyDerivationMech must be supported
        * for these services to be available.
        *
        */
        d(MAC, "HmacPBESHA1",       P11Mac, m(CKM_SHA_1_HMAC),
                m(CKM_PBA_SHA1_WITH_SHA1_HMAC));
        d(MAC, "HmacPBESHA224",     P11Mac, m(CKM_SHA224_HMAC),
                m(CKM_NSS_PKCS12_PBE_SHA224_HMAC_KEY_GEN));
        d(MAC, "HmacPBESHA256",     P11Mac, m(CKM_SHA256_HMAC),
                m(CKM_NSS_PKCS12_PBE_SHA256_HMAC_KEY_GEN));
        d(MAC, "HmacPBESHA384",     P11Mac, m(CKM_SHA384_HMAC),
                m(CKM_NSS_PKCS12_PBE_SHA384_HMAC_KEY_GEN));
        d(MAC, "HmacPBESHA512",     P11Mac, m(CKM_SHA512_HMAC),
                m(CKM_NSS_PKCS12_PBE_SHA512_HMAC_KEY_GEN));

        d(KPG, "RSA",           P11KeyPairGenerator,
                getAliases("PKCS1"),
                m(CKM_RSA_PKCS_KEY_PAIR_GEN));

        List<String> dhAlias = List.of("DiffieHellman");

        dA(KPG, "DSA",           P11KeyPairGenerator,
                m(CKM_DSA_KEY_PAIR_GEN));
        d(KPG, "DH",            P11KeyPairGenerator,
                dhAlias,
                m(CKM_DH_PKCS_KEY_PAIR_GEN));
        d(KPG, "EC",            P11KeyPairGenerator,
                m(CKM_EC_KEY_PAIR_GEN));

        dA(KG,  "ARCFOUR",       P11KeyGenerator,
                m(CKM_RC4_KEY_GEN));
        d(KG,  "DES",           P11KeyGenerator,
                m(CKM_DES_KEY_GEN));
        d(KG,  "DESede",        P11KeyGenerator,
                m(CKM_DES3_KEY_GEN, CKM_DES2_KEY_GEN));
        d(KG,  "AES",           P11KeyGenerator,
                m(CKM_AES_KEY_GEN));
        d(KG,  "Blowfish",      P11KeyGenerator,
                m(CKM_BLOWFISH_KEY_GEN));
        d(KG,  "ChaCha20",      P11KeyGenerator,
                m(CKM_CHACHA20_KEY_GEN));
        d(KG,  "HmacMD5",      P11KeyGenerator, // 1.3.6.1.5.5.8.1.1
                m(CKM_GENERIC_SECRET_KEY_GEN));
        dA(KG,  "HmacSHA1",      P11KeyGenerator,
                m(CKM_SHA_1_KEY_GEN, CKM_GENERIC_SECRET_KEY_GEN));
        dA(KG,  "HmacSHA224",    P11KeyGenerator,
                m(CKM_SHA224_KEY_GEN, CKM_GENERIC_SECRET_KEY_GEN));
        dA(KG,  "HmacSHA256",    P11KeyGenerator,
                m(CKM_SHA256_KEY_GEN, CKM_GENERIC_SECRET_KEY_GEN));
        dA(KG,  "HmacSHA384",    P11KeyGenerator,
                m(CKM_SHA384_KEY_GEN, CKM_GENERIC_SECRET_KEY_GEN));
        dA(KG,  "HmacSHA512",    P11KeyGenerator,
                m(CKM_SHA512_KEY_GEN, CKM_GENERIC_SECRET_KEY_GEN));
        dA(KG,  "HmacSHA512/224",    P11KeyGenerator,
                m(CKM_SHA512_224_KEY_GEN, CKM_GENERIC_SECRET_KEY_GEN));
        dA(KG,  "HmacSHA512/256",    P11KeyGenerator,
                m(CKM_SHA512_256_KEY_GEN, CKM_GENERIC_SECRET_KEY_GEN));
        dA(KG,  "HmacSHA3-224",    P11KeyGenerator,
                m(CKM_SHA3_224_KEY_GEN, CKM_GENERIC_SECRET_KEY_GEN));
        dA(KG,  "HmacSHA3-256",    P11KeyGenerator,
                m(CKM_SHA3_256_KEY_GEN, CKM_GENERIC_SECRET_KEY_GEN));
        dA(KG,  "HmacSHA3-384",    P11KeyGenerator,
                m(CKM_SHA3_384_KEY_GEN, CKM_GENERIC_SECRET_KEY_GEN));
        dA(KG,  "HmacSHA3-512",    P11KeyGenerator,
                m(CKM_SHA3_512_KEY_GEN, CKM_GENERIC_SECRET_KEY_GEN));

        // register (Secret)KeyFactories if there are any mechanisms
        // for a particular algorithm that we support
        d(KF, "RSA",            P11RSAKeyFactory,
                getAliases("PKCS1"),
                m(CKM_RSA_PKCS_KEY_PAIR_GEN, CKM_RSA_PKCS, CKM_RSA_X_509));
        dA(KF, "DSA",            P11DSAKeyFactory,
                m(CKM_DSA_KEY_PAIR_GEN, CKM_DSA, CKM_DSA_SHA1));
        d(KF, "DH",             P11DHKeyFactory,
                dhAlias,
                m(CKM_DH_PKCS_KEY_PAIR_GEN, CKM_DH_PKCS_DERIVE));
        d(KF, "EC",             P11ECKeyFactory,
                m(CKM_EC_KEY_PAIR_GEN, CKM_ECDH1_DERIVE,
                    CKM_ECDSA, CKM_ECDSA_SHA1));

        // AlgorithmParameters for EC.
        // Only needed until we have an EC implementation in the SUN provider.
        dA(AGP, "EC",            "sun.security.util.ECParameters",
                m(CKM_EC_KEY_PAIR_GEN, CKM_ECDH1_DERIVE,
                    CKM_ECDSA, CKM_ECDSA_SHA1));


        d(AGP, "GCM",            "sun.security.util.GCMParameters",
                m(CKM_AES_GCM));

        dA(AGP, "ChaCha20-Poly1305",
                "com.sun.crypto.provider.ChaCha20Poly1305Parameters",
                m(CKM_CHACHA20_POLY1305));

        dA(AGP, "RSASSA-PSS",
                "sun.security.rsa.PSSParameters",
                m(CKM_RSA_PKCS_PSS));

        dA(AGP, "DiffieHellman",
                "com.sun.crypto.provider.DHParameters",
                m(CKM_DH_PKCS_DERIVE));

        d(KA, "DH",             P11KeyAgreement,
                dhAlias,
                m(CKM_DH_PKCS_DERIVE));
        d(KA, "ECDH",           "sun.security.pkcs11.P11ECDHKeyAgreement",
                m(CKM_ECDH1_DERIVE));

        dA(SKF, "ARCFOUR",       P11SecretKeyFactory,
                m(CKM_RC4));
        d(SKF, "DES",           P11SecretKeyFactory,
                m(CKM_DES_CBC));
        d(SKF, "DESede",        P11SecretKeyFactory,
                m(CKM_DES3_CBC));
        dA(SKF, "AES",           P11SecretKeyFactory,
                m(CKM_AES_CBC));
        d(SKF, "Blowfish",      P11SecretKeyFactory,
                m(CKM_BLOWFISH_CBC));
        d(SKF, "ChaCha20",      P11SecretKeyFactory,
                m(CKM_CHACHA20_POLY1305));
        d(SKF, "Generic",       P11SecretKeyFactory,
                m(CKM_GENERIC_SECRET_KEY_GEN));

        /*
         * PBKDF2 Secret Key Factories
         */
        dA(SKF, "PBKDF2WithHmacSHA1",  P11SecretKeyFactory,
                m(CKM_PKCS5_PBKD2), m(CKM_SHA_1_HMAC));
        d(SKF, "PBKDF2WithHmacSHA224", P11SecretKeyFactory,
                m(CKM_PKCS5_PBKD2), m(CKM_SHA224_HMAC));
        d(SKF, "PBKDF2WithHmacSHA256", P11SecretKeyFactory,
                m(CKM_PKCS5_PBKD2), m(CKM_SHA256_HMAC));
        d(SKF, "PBKDF2WithHmacSHA384", P11SecretKeyFactory,
                m(CKM_PKCS5_PBKD2), m(CKM_SHA384_HMAC));
        d(SKF, "PBKDF2WithHmacSHA512", P11SecretKeyFactory,
                m(CKM_PKCS5_PBKD2), m(CKM_SHA512_HMAC));

        // XXX attributes for Ciphers (supported modes, padding)
        dA(CIP, "ARCFOUR",                      P11Cipher,
                m(CKM_RC4));
        d(CIP, "DES/CBC/NoPadding",             P11Cipher,
                m(CKM_DES_CBC));
        d(CIP, "DES/CBC/PKCS5Padding",          P11Cipher,
                m(CKM_DES_CBC_PAD, CKM_DES_CBC));
        d(CIP, "DES/ECB/NoPadding",             P11Cipher,
                m(CKM_DES_ECB));
        d(CIP, "DES/ECB/PKCS5Padding",          P11Cipher,
                List.of("DES"),
                m(CKM_DES_ECB));

        d(CIP, "DESede/CBC/NoPadding",          P11Cipher,
                m(CKM_DES3_CBC));
        d(CIP, "DESede/CBC/PKCS5Padding",       P11Cipher,
                m(CKM_DES3_CBC_PAD, CKM_DES3_CBC));
        d(CIP, "DESede/ECB/NoPadding",          P11Cipher,
                m(CKM_DES3_ECB));
        d(CIP, "DESede/ECB/PKCS5Padding",       P11Cipher,
                List.of("DESede"),
                m(CKM_DES3_ECB));
        d(CIP, "AES/CBC/NoPadding",             P11Cipher,
                m(CKM_AES_CBC));
        dA(CIP, "AES_128/CBC/NoPadding",        P11Cipher,
                m(CKM_AES_CBC));
        dA(CIP, "AES_192/CBC/NoPadding",        P11Cipher,
                m(CKM_AES_CBC));
        dA(CIP, "AES_256/CBC/NoPadding",        P11Cipher,
                m(CKM_AES_CBC));
        d(CIP, "AES/CBC/PKCS5Padding",          P11Cipher,
                m(CKM_AES_CBC_PAD, CKM_AES_CBC));
        d(CIP, "AES/ECB/NoPadding",             P11Cipher,
                m(CKM_AES_ECB));
        dA(CIP, "AES_128/ECB/NoPadding",        P11Cipher,
                m(CKM_AES_ECB));
        dA(CIP, "AES_192/ECB/NoPadding",        P11Cipher,
                m(CKM_AES_ECB));
        dA(CIP, "AES_256/ECB/NoPadding",        P11Cipher,
                m(CKM_AES_ECB));
        d(CIP, "AES/ECB/PKCS5Padding",          P11Cipher,
                List.of("AES"),
                m(CKM_AES_ECB));
        d(CIP, "AES/CTR/NoPadding",             P11Cipher,
                m(CKM_AES_CTR));
        dA(CIP, "AES/KW/NoPadding",             P11KeyWrapCipher,
                m(CKM_AES_KEY_WRAP));
        dA(CIP, "AES_128/KW/NoPadding",         P11KeyWrapCipher,
                m(CKM_AES_KEY_WRAP));
        dA(CIP, "AES_192/KW/NoPadding",         P11KeyWrapCipher,
                m(CKM_AES_KEY_WRAP));
        dA(CIP, "AES_256/KW/NoPadding",         P11KeyWrapCipher,
                m(CKM_AES_KEY_WRAP));
        d(CIP, "AES/KW/PKCS5Padding",           P11KeyWrapCipher,
                m(CKM_AES_KEY_WRAP_PAD));
        d(CIP, "AES_128/KW/PKCS5Padding",       P11KeyWrapCipher,
                m(CKM_AES_KEY_WRAP_PAD));
        d(CIP, "AES_192/KW/PKCS5Padding",       P11KeyWrapCipher,
                m(CKM_AES_KEY_WRAP_PAD));
        d(CIP, "AES_256/KW/PKCS5Padding",       P11KeyWrapCipher,
                m(CKM_AES_KEY_WRAP_PAD));
        dA(CIP, "AES/KWP/NoPadding",            P11KeyWrapCipher,
                m(CKM_AES_KEY_WRAP_KWP));
        dA(CIP, "AES_128/KWP/NoPadding",        P11KeyWrapCipher,
                m(CKM_AES_KEY_WRAP_KWP));
        dA(CIP, "AES_192/KWP/NoPadding",        P11KeyWrapCipher,
                m(CKM_AES_KEY_WRAP_KWP));
        dA(CIP, "AES_256/KWP/NoPadding",        P11KeyWrapCipher,
                m(CKM_AES_KEY_WRAP_KWP));

        d(CIP, "AES/CTS/NoPadding",             P11Cipher,
                m(CKM_AES_CTS));
        d(CIP, "AES_128/CTS/NoPadding",         P11Cipher,
                m(CKM_AES_CTS));
        d(CIP, "AES_192/CTS/NoPadding",         P11Cipher,
                m(CKM_AES_CTS));
        d(CIP, "AES_256/CTS/NoPadding",         P11Cipher,
                m(CKM_AES_CTS));

        d(CIP, "AES/GCM/NoPadding",             P11AEADCipher,
                m(CKM_AES_GCM));
        dA(CIP, "AES_128/GCM/NoPadding",        P11AEADCipher,
                m(CKM_AES_GCM));
        dA(CIP, "AES_192/GCM/NoPadding",        P11AEADCipher,
                m(CKM_AES_GCM));
        dA(CIP, "AES_256/GCM/NoPadding",        P11AEADCipher,
                m(CKM_AES_GCM));

        d(CIP, "Blowfish/CBC/NoPadding",        P11Cipher,
                m(CKM_BLOWFISH_CBC));
        d(CIP, "Blowfish/CBC/PKCS5Padding",     P11Cipher,
                m(CKM_BLOWFISH_CBC));

        dA(CIP, "ChaCha20-Poly1305",            P11AEADCipher,
                m(CKM_CHACHA20_POLY1305));

        d(CIP, "RSA/ECB/PKCS1Padding",          P11RSACipher,
                List.of("RSA"),
                m(CKM_RSA_PKCS));
        d(CIP, "RSA/ECB/NoPadding",             P11RSACipher,
                m(CKM_RSA_X_509));

        /*
         * PBE Ciphers
         *
         * KeyDerivationMech and KeyDerivationPrf must be supported
         * for these services to be available.
         *
        */
        d(CIP, "PBEWithHmacSHA1AndAES_128",   P11PBECipher,
                m(CKM_AES_CBC_PAD, CKM_AES_CBC),
                m(CKM_PKCS5_PBKD2, CKM_SHA_1_HMAC));
        d(CIP, "PBEWithHmacSHA224AndAES_128", P11PBECipher,
                m(CKM_AES_CBC_PAD, CKM_AES_CBC),
                m(CKM_PKCS5_PBKD2, CKM_SHA224_HMAC));
        d(CIP, "PBEWithHmacSHA256AndAES_128", P11PBECipher,
                m(CKM_AES_CBC_PAD, CKM_AES_CBC),
                m(CKM_PKCS5_PBKD2, CKM_SHA256_HMAC));
        d(CIP, "PBEWithHmacSHA384AndAES_128", P11PBECipher,
                m(CKM_AES_CBC_PAD, CKM_AES_CBC),
                m(CKM_PKCS5_PBKD2, CKM_SHA384_HMAC));
        d(CIP, "PBEWithHmacSHA512AndAES_128", P11PBECipher,
                m(CKM_AES_CBC_PAD, CKM_AES_CBC),
                m(CKM_PKCS5_PBKD2, CKM_SHA512_HMAC));
        d(CIP, "PBEWithHmacSHA1AndAES_256",   P11PBECipher,
                m(CKM_AES_CBC_PAD, CKM_AES_CBC),
                m(CKM_PKCS5_PBKD2, CKM_SHA_1_HMAC));
        d(CIP, "PBEWithHmacSHA224AndAES_256", P11PBECipher,
                m(CKM_AES_CBC_PAD, CKM_AES_CBC),
                m(CKM_PKCS5_PBKD2, CKM_SHA224_HMAC));
        d(CIP, "PBEWithHmacSHA256AndAES_256", P11PBECipher,
                m(CKM_AES_CBC_PAD, CKM_AES_CBC),
                m(CKM_PKCS5_PBKD2, CKM_SHA256_HMAC));
        d(CIP, "PBEWithHmacSHA384AndAES_256", P11PBECipher,
                m(CKM_AES_CBC_PAD, CKM_AES_CBC),
                m(CKM_PKCS5_PBKD2, CKM_SHA384_HMAC));
        d(CIP, "PBEWithHmacSHA512AndAES_256", P11PBECipher,
                m(CKM_AES_CBC_PAD, CKM_AES_CBC),
                m(CKM_PKCS5_PBKD2, CKM_SHA512_HMAC));

        d(SIG, "RawDSA",        P11Signature,
                List.of("NONEwithDSA"),
                m(CKM_DSA));
        dA(SIG, "SHA1withDSA",           P11Signature,
                m(CKM_DSA_SHA1, CKM_DSA));
        dA(SIG, "SHA224withDSA", P11Signature,
                m(CKM_DSA_SHA224));
        dA(SIG, "SHA256withDSA", P11Signature,
                m(CKM_DSA_SHA256));
        dA(SIG, "SHA384withDSA", P11Signature,
                m(CKM_DSA_SHA384));
        dA(SIG, "SHA512withDSA", P11Signature,
                m(CKM_DSA_SHA512));
        dA(SIG, "SHA3-224withDSA", P11Signature,
                m(CKM_DSA_SHA3_224));
        dA(SIG, "SHA3-256withDSA", P11Signature,
                m(CKM_DSA_SHA3_256));
        dA(SIG, "SHA3-384withDSA", P11Signature,
                m(CKM_DSA_SHA3_384));
        dA(SIG, "SHA3-512withDSA", P11Signature,
                m(CKM_DSA_SHA3_512));
        d(SIG, "RawDSAinP1363Format",   P11Signature,
                List.of("NONEwithDSAinP1363Format"),
                m(CKM_DSA));
        d(SIG, "DSAinP1363Format",      P11Signature,
                List.of("SHA1withDSAinP1363Format"),
                m(CKM_DSA_SHA1, CKM_DSA));
        d(SIG, "SHA224withDSAinP1363Format",      P11Signature,
                m(CKM_DSA_SHA224));
        d(SIG, "SHA256withDSAinP1363Format",      P11Signature,
                m(CKM_DSA_SHA256));
        d(SIG, "SHA384withDSAinP1363Format",      P11Signature,
                m(CKM_DSA_SHA384));
        d(SIG, "SHA512withDSAinP1363Format",      P11Signature,
                m(CKM_DSA_SHA512));
        d(SIG, "SHA3-224withDSAinP1363Format",      P11Signature,
                m(CKM_DSA_SHA3_224));
        d(SIG, "SHA3-256withDSAinP1363Format",      P11Signature,
                m(CKM_DSA_SHA3_256));
        d(SIG, "SHA3-384withDSAinP1363Format",      P11Signature,
                m(CKM_DSA_SHA3_384));
        d(SIG, "SHA3-512withDSAinP1363Format",      P11Signature,
                m(CKM_DSA_SHA3_512));
        d(SIG, "NONEwithECDSA", P11Signature,
                m(CKM_ECDSA));
        dA(SIG, "SHA1withECDSA", P11Signature,
                m(CKM_ECDSA_SHA1, CKM_ECDSA));
        dA(SIG, "SHA224withECDSA",       P11Signature,
                m(CKM_ECDSA_SHA224, CKM_ECDSA));
        dA(SIG, "SHA256withECDSA",       P11Signature,
                m(CKM_ECDSA_SHA256, CKM_ECDSA));
        dA(SIG, "SHA384withECDSA",       P11Signature,
                m(CKM_ECDSA_SHA384, CKM_ECDSA));
        dA(SIG, "SHA512withECDSA",       P11Signature,
                m(CKM_ECDSA_SHA512, CKM_ECDSA));
        dA(SIG, "SHA3-224withECDSA",       P11Signature,
                m(CKM_ECDSA_SHA3_224, CKM_ECDSA));
        dA(SIG, "SHA3-256withECDSA",       P11Signature,
                m(CKM_ECDSA_SHA3_256, CKM_ECDSA));
        dA(SIG, "SHA3-384withECDSA",       P11Signature,
                m(CKM_ECDSA_SHA3_384, CKM_ECDSA));
        dA(SIG, "SHA3-512withECDSA",       P11Signature,
                m(CKM_ECDSA_SHA3_512, CKM_ECDSA));
        d(SIG, "NONEwithECDSAinP1363Format",   P11Signature,
                m(CKM_ECDSA));
        d(SIG, "SHA1withECDSAinP1363Format",   P11Signature,
                m(CKM_ECDSA_SHA1, CKM_ECDSA));
        d(SIG, "SHA224withECDSAinP1363Format", P11Signature,
                m(CKM_ECDSA_SHA224, CKM_ECDSA));
        d(SIG, "SHA256withECDSAinP1363Format", P11Signature,
                m(CKM_ECDSA_SHA256, CKM_ECDSA));
        d(SIG, "SHA384withECDSAinP1363Format", P11Signature,
                m(CKM_ECDSA_SHA384, CKM_ECDSA));
        d(SIG, "SHA512withECDSAinP1363Format", P11Signature,
                m(CKM_ECDSA_SHA512, CKM_ECDSA));
        d(SIG, "SHA3-224withECDSAinP1363Format", P11Signature,
                m(CKM_ECDSA_SHA3_224, CKM_ECDSA));
        d(SIG, "SHA3-256withECDSAinP1363Format", P11Signature,
                m(CKM_ECDSA_SHA3_256, CKM_ECDSA));
        d(SIG, "SHA3-384withECDSAinP1363Format", P11Signature,
                m(CKM_ECDSA_SHA3_384, CKM_ECDSA));
        d(SIG, "SHA3-512withECDSAinP1363Format", P11Signature,
                m(CKM_ECDSA_SHA3_512, CKM_ECDSA));

        dA(SIG, "MD2withRSA",    P11Signature,
                m(CKM_MD2_RSA_PKCS, CKM_RSA_PKCS, CKM_RSA_X_509));
        dA(SIG, "MD5withRSA",    P11Signature,
                m(CKM_MD5_RSA_PKCS, CKM_RSA_PKCS, CKM_RSA_X_509));
        dA(SIG, "SHA1withRSA",   P11Signature,
                m(CKM_SHA1_RSA_PKCS, CKM_RSA_PKCS, CKM_RSA_X_509));
        dA(SIG, "SHA224withRSA", P11Signature,
                m(CKM_SHA224_RSA_PKCS, CKM_RSA_PKCS, CKM_RSA_X_509));
        dA(SIG, "SHA256withRSA", P11Signature,
                m(CKM_SHA256_RSA_PKCS, CKM_RSA_PKCS, CKM_RSA_X_509));
        dA(SIG, "SHA384withRSA", P11Signature,
                m(CKM_SHA384_RSA_PKCS, CKM_RSA_PKCS, CKM_RSA_X_509));
        dA(SIG, "SHA512withRSA", P11Signature,
                m(CKM_SHA512_RSA_PKCS, CKM_RSA_PKCS, CKM_RSA_X_509));
        dA(SIG, "SHA3-224withRSA", P11Signature,
                m(CKM_SHA3_224_RSA_PKCS, CKM_RSA_PKCS, CKM_RSA_X_509));
        dA(SIG, "SHA3-256withRSA", P11Signature,
                m(CKM_SHA3_256_RSA_PKCS, CKM_RSA_PKCS, CKM_RSA_X_509));
        dA(SIG, "SHA3-384withRSA", P11Signature,
                m(CKM_SHA3_384_RSA_PKCS, CKM_RSA_PKCS, CKM_RSA_X_509));
        dA(SIG, "SHA3-512withRSA", P11Signature,
                m(CKM_SHA3_512_RSA_PKCS, CKM_RSA_PKCS, CKM_RSA_X_509));
        dA(SIG, "RSASSA-PSS", P11PSSSignature,
                m(CKM_RSA_PKCS_PSS));
        d(SIG, "SHA1withRSASSA-PSS", P11PSSSignature,
                m(CKM_SHA1_RSA_PKCS_PSS));
        d(SIG, "SHA224withRSASSA-PSS", P11PSSSignature,
                m(CKM_SHA224_RSA_PKCS_PSS));
        d(SIG, "SHA256withRSASSA-PSS", P11PSSSignature,
                m(CKM_SHA256_RSA_PKCS_PSS));
        d(SIG, "SHA384withRSASSA-PSS", P11PSSSignature,
                m(CKM_SHA384_RSA_PKCS_PSS));
        d(SIG, "SHA512withRSASSA-PSS", P11PSSSignature,
                m(CKM_SHA512_RSA_PKCS_PSS));
        d(SIG, "SHA3-224withRSASSA-PSS", P11PSSSignature,
                m(CKM_SHA3_224_RSA_PKCS_PSS));
        d(SIG, "SHA3-256withRSASSA-PSS", P11PSSSignature,
                m(CKM_SHA3_256_RSA_PKCS_PSS));
        d(SIG, "SHA3-384withRSASSA-PSS", P11PSSSignature,
                m(CKM_SHA3_384_RSA_PKCS_PSS));
        d(SIG, "SHA3-512withRSASSA-PSS", P11PSSSignature,
                m(CKM_SHA3_512_RSA_PKCS_PSS));

        d(KG, "SunTlsRsaPremasterSecret",
                    "sun.security.pkcs11.P11TlsRsaPremasterSecretGenerator",
                List.of("SunTls12RsaPremasterSecret"),
                m(CKM_SSL3_PRE_MASTER_KEY_GEN, CKM_TLS_PRE_MASTER_KEY_GEN));
        d(KG, "SunTlsMasterSecret",
                    "sun.security.pkcs11.P11TlsMasterSecretGenerator",
                m(CKM_SSL3_MASTER_KEY_DERIVE, CKM_TLS_MASTER_KEY_DERIVE,
                    CKM_SSL3_MASTER_KEY_DERIVE_DH,
                    CKM_TLS_MASTER_KEY_DERIVE_DH));
        d(KG, "SunTls12MasterSecret",
                "sun.security.pkcs11.P11TlsMasterSecretGenerator",
            m(CKM_TLS12_MASTER_KEY_DERIVE, CKM_TLS12_MASTER_KEY_DERIVE_DH));
        d(KG, "SunTlsKeyMaterial",
                    "sun.security.pkcs11.P11TlsKeyMaterialGenerator",
                m(CKM_SSL3_KEY_AND_MAC_DERIVE, CKM_TLS_KEY_AND_MAC_DERIVE));
        d(KG, "SunTls12KeyMaterial",
                "sun.security.pkcs11.P11TlsKeyMaterialGenerator",
            m(CKM_TLS12_KEY_AND_MAC_DERIVE));
        d(KG, "SunTlsPrf", "sun.security.pkcs11.P11TlsPrfGenerator",
                m(CKM_TLS_PRF, CKM_NSS_TLS_PRF_GENERAL));
        d(KG, "SunTls12Prf", "sun.security.pkcs11.P11TlsPrfGenerator",
                m(CKM_TLS_MAC));

        d(KDF, "HKDF-SHA256", P11HKDF, m(CKM_HKDF_DERIVE, CKM_HKDF_DATA),
                m(CKM_SHA256_HMAC));
        d(KDF, "HKDF-SHA384", P11HKDF, m(CKM_HKDF_DERIVE, CKM_HKDF_DATA),
                m(CKM_SHA384_HMAC));
        d(KDF, "HKDF-SHA512", P11HKDF, m(CKM_HKDF_DERIVE, CKM_HKDF_DATA),
                m(CKM_SHA512_HMAC));
    }

    // background thread that periodically checks for token insertion
    // if no token is present. We need to do that in a separate thread because
    // the insertion check may block for quite a long time on some tokens.
    private static class TokenPoller implements Runnable {
        private final SunPKCS11 provider;
        private volatile boolean enabled;

        private TokenPoller(SunPKCS11 provider) {
            this.provider = provider;
            enabled = true;
        }
        @Override
        public void run() {
            int interval = provider.config.getInsertionCheckInterval();
            while (enabled) {
                try {
                    Thread.sleep(interval);
                } catch (InterruptedException e) {
                    break;
                }
                if (!enabled) {
                    break;
                }
                try {
                    provider.initToken(null);
                } catch (PKCS11Exception e) {
                    // ignore
                }
            }
        }
        void disable() {
            enabled = false;
        }
    }

    // create the poller thread, if not already active
    private void createPoller() {
        if (poller != null) {
            return;
        }
        poller = new TokenPoller(this);
        Thread t = InnocuousThread.newSystemThread(
                "Poller-" + getName(),
                poller,
                Thread.MIN_PRIORITY);
        assert t.getContextClassLoader() == null;
        t.setDaemon(true);
        t.start();

    }

    // destroy the poller thread, if active
    private void destroyPoller() {
        if (poller != null) {
            poller.disable();
            poller = null;
        }
    }

    private boolean hasValidToken() {
        /* Commented out to work with Solaris softtoken impl which
           returns 0-value flags, e.g. both REMOVABLE_DEVICE and
           TOKEN_PRESENT are false, when it can't access the token.
        if (removable == false) {
            return true;
        }
        */
        Token token = this.token;
        return (token != null) && token.isValid();
    }

    private class NativeResourceCleaner implements Runnable {
        private long sleepMillis = config.getResourceCleanerShortInterval();
        private int count = 0;
        boolean keyRefFound, sessRefFound;

        /*
         * The cleaner.shortInterval and cleaner.longInterval properties
         * may be defined in the pkcs11 config file and are specified in milliseconds
         * Minimum value is 1000ms.  Default values :
         *  cleaner.shortInterval : 2000ms
         *  cleaner.longInterval  : 60000ms
         *
         * The cleaner thread runs at cleaner.shortInterval intervals
         * while P11Key or Session references continue to be found for cleaning.
         * If 100 iterations occur with no references being found, then the interval
         * period moves to cleaner.longInterval value. The cleaner thread moves back
         * to short interval checking if a resource is found
         */
        @Override
        public void run() {
            while (true) {
                try {
                    Thread.sleep(sleepMillis);
                } catch (InterruptedException ie) {
                    break;
                }
                keyRefFound = P11Key.drainRefQueue();
                sessRefFound = Session.drainRefQueue();
                if (!keyRefFound && !sessRefFound) {
                    count++;
                    if (count > 100) {
                        // no reference freed for some time
                        // increase the sleep time
                        sleepMillis = config.getResourceCleanerLongInterval();
                    }
                } else {
                    count = 0;
                    sleepMillis = config.getResourceCleanerShortInterval();
                }
            }
        }
    }

    // create the cleaner thread, if not already active
    private void createCleaner() {
        cleaner = new NativeResourceCleaner();
        Thread t = InnocuousThread.newSystemThread(
                "Cleanup-SunPKCS11",
                cleaner,
                Thread.MIN_PRIORITY);
        assert t.getContextClassLoader() == null;
        t.setDaemon(true);
        t.start();
    }

    // destroy the token. Called if we detect that it has been removed
    synchronized void uninitToken(Token token) {
        if (this.token != token) {
            // mismatch, our token must already be destroyed
            return;
        }
        destroyPoller();
        this.token = null;
        // unregister all algorithms
        clear();
        // keep polling for token insertion unless configured not to
        if (removable && !config.getDestroyTokenAfterLogout()) {
            createPoller();
        }
    }

    // test if a token is present and initialize this provider for it if so.
    // does nothing if no token is found
    // called from constructor and by poller
    private void initToken(CK_SLOT_INFO slotInfo) throws PKCS11Exception {
        if (slotInfo == null) {
            slotInfo = p11.C_GetSlotInfo(slotID);
        }
        if (removable && (slotInfo.flags & CKF_TOKEN_PRESENT) == 0) {
            createPoller();
            return;
        }
        destroyPoller();
        boolean showInfo = config.getShowInfo();
        if (showInfo) {
            System.out.println("Slot info for slot " + slotID + ":");
            System.out.println(slotInfo);
        }
        final Token token = new Token(this);
        if (showInfo) {
            System.out.println
                ("Token info for token in slot " + slotID + ":");
            System.out.println(token.tokenInfo);
        }
        Set<Long> brokenMechanisms = Set.of();
        if (P11Util.isNSS(token)) {
            CK_VERSION nssVersion = slotInfo.hardwareVersion;
            if (nssVersion.major < 3 ||
                    nssVersion.major == 3 && nssVersion.minor < 65) {
                // RSA-PSS is broken in NSS prior to 3.65
                brokenMechanisms = Set.of(CKM_RSA_PKCS_PSS,
                        CKM_SHA1_RSA_PKCS_PSS,
                        CKM_SHA224_RSA_PKCS_PSS,
                        CKM_SHA256_RSA_PKCS_PSS,
                        CKM_SHA384_RSA_PKCS_PSS,
                        CKM_SHA512_RSA_PKCS_PSS,
                        CKM_SHA3_224_RSA_PKCS_PSS,
                        CKM_SHA3_256_RSA_PKCS_PSS,
                        CKM_SHA3_384_RSA_PKCS_PSS,
                        CKM_SHA3_512_RSA_PKCS_PSS);
            }
        }

        long[] supportedMechanisms = p11.C_GetMechanismList(slotID);
        Set<Long> supportedMechSet =
                Arrays.stream(supportedMechanisms).boxed().collect
                (Collectors.toCollection(HashSet::new));

        // Create a map from the various Descriptors to the "most
        // preferred" mechanism that was defined during the
        // static initialization.  For example, DES/CBC/PKCS5Padding
        // could be mapped to CKM_DES_CBC_PAD or CKM_DES_CBC.  Prefer
        // the earliest entry.  When asked for "DES/CBC/PKCS5Padding", we
        // return a CKM_DES_CBC_PAD.
        final Map<Descriptor,Integer> supportedAlgs =
                new HashMap<Descriptor,Integer>();

        for (long longMech : supportedMechanisms) {
            CK_MECHANISM_INFO mechInfo = token.getMechanismInfo(longMech);
            if (showInfo) {
                System.out.println("Mechanism " +
                    Functions.getMechanismName(longMech) + ":");
                System.out.println(mechInfo == null?
                    (Constants.INDENT + "info n/a") :
                    mechInfo);
            }
            if (!config.isEnabled(longMech)) {
                if (showInfo) {
                    System.out.println("DISABLED in configuration");
                }
                continue;
            }
            if (longMech == CKM_AES_CTS && token.ctsVariant == null) {
                if (showInfo) {
                    System.out.println("DISABLED due to an unspecified " +
                            "cipherTextStealingVariant in configuration");
                }
                continue;
            }
            if (brokenMechanisms.contains(longMech)) {
                if (showInfo) {
                    System.out.println("DISABLED due to known issue with NSS");
                }
                continue;
            }

            // we do not know of mechs with the upper 32 bits set
            if (longMech >>> 32 != 0) {
                if (showInfo) {
                    System.out.println("DISABLED due to unknown mech value");
                }
                continue;
            }
            int mech = (int)longMech;
            Integer integerMech = Integer.valueOf(mech);
            List<Descriptor> ds = descriptors.get(integerMech);
            if (ds == null) {
                continue;
            }
            boolean allowLegacy = config.getAllowLegacy();
            descLoop:
            for (Descriptor d : ds) {
                Integer oldMech = supportedAlgs.get(d);
                if (oldMech == null) {
                    // check all required mechs are supported
                    if (d.requiredMechs != null) {
                        for (int reqMech : d.requiredMechs) {
                            long longReqMech = reqMech & 0xFFFFFFFFL;
                            if (!config.isEnabled(longReqMech) ||
                                    !supportedMechSet.contains(longReqMech) ||
                                    brokenMechanisms.contains(longReqMech)) {
                                if (showInfo) {
                                    System.out.println("DISABLED " + d.type +
                                        " " + d.algorithm +
                                        " due to no support for req'd mech " +
                                        Functions.getMechanismName(longReqMech));
                                }
                                continue descLoop;
                            }
                        }
                    }

                    // assume full support if no mech info available
                    if (!allowLegacy && mechInfo != null) {
                        if ((d.type == CIP &&
                                (mechInfo.flags & CKF_ENCRYPT) == 0) ||
                                (d.type == SIG &&
                                (mechInfo.flags & CKF_SIGN) == 0)) {
                            if (showInfo) {
                                System.out.println("DISABLED " + d.type +
                                        " " + d.algorithm +
                                        " due to partial support");
                            }
                            continue;
                        }
                    }
                    supportedAlgs.put(d, integerMech);
                    continue;
                }
                // See if there is something "more preferred"
                // than what we currently have in the supportedAlgs
                // map.
                int intOldMech = oldMech.intValue();
                for (int j = 0; j < d.mechanisms.length; j++) {
                    int nextMech = d.mechanisms[j];
                    if (mech == nextMech) {
                        supportedAlgs.put(d, integerMech);
                        break;
                    } else if (intOldMech == nextMech) {
                        break;
                    }
                }
            }
        }

        // register algorithms in provider
        for (Map.Entry<Descriptor,Integer> entry : supportedAlgs.entrySet()) {
            Descriptor d = entry.getKey();
            int mechanism = entry.getValue().intValue();
            Service s = d.service(token, mechanism);
            putService(s);
        }
        if (((token.tokenInfo.flags & CKF_RNG) != 0)
                && config.isEnabled(PCKM_SECURERANDOM)
                && !token.sessionManager.lowMaxSessions()) {
            // do not register SecureRandom if the token does
            // not support many sessions. if we did, we might
            // run out of sessions in the middle of a
            // nextBytes() call where we cannot fail over.
            putService(new P11Service(token, SR, "PKCS11",
                "sun.security.pkcs11.P11SecureRandom", null,
                PCKM_SECURERANDOM));
        }
        if (config.isEnabled(PCKM_KEYSTORE)) {
            putService(new P11Service(token, KS, "PKCS11",
                "sun.security.pkcs11.P11KeyStore",
                List.of("PKCS11-" + config.getName()),
                PCKM_KEYSTORE));
        }

        this.token = token;
        if (cleaner == null) {
            createCleaner();
        }
    }

    private static final class P11Service extends Service {

        private final Token token;

        private final long mechanism;

        P11Service(Token token, String type, String algorithm,
                String className, List<String> al, long mechanism) {
            super(token.provider, type, algorithm, className, al,
                    type.equals(SR) ? Map.of("ThreadSafe", "true") : null);
            this.token = token;
            this.mechanism = mechanism & 0xFFFFFFFFL;
        }

        @Override
        public Object newInstance(Object param)
                throws NoSuchAlgorithmException {
            if (!token.isValid()) {
                throw new NoSuchAlgorithmException("Token has been removed");
            }
            try {
                return newInstance0(param);
            } catch (PKCS11Exception e) {
                throw new NoSuchAlgorithmException(e);
            }
        }

        public Object newInstance0(Object param) throws
                PKCS11Exception, NoSuchAlgorithmException {
            String algorithm = getAlgorithm();
            String type = getType();
            if (type == MD) {
                return new P11Digest(token, algorithm, mechanism);
            } else if (type == CIP) {
                if (algorithm.startsWith("RSA")) {
                    return new P11RSACipher(token, algorithm, mechanism);
                } else if (algorithm.endsWith("GCM/NoPadding") ||
                           algorithm.startsWith("ChaCha20-Poly1305")) {
                    return new P11AEADCipher(token, algorithm, mechanism);
                } else if (algorithm.contains("/KW/") ||
                        algorithm.contains("/KWP/")) {
                    return new P11KeyWrapCipher(token, algorithm, mechanism);
                } else if (algorithm.startsWith("PBE")) {
                    return new P11PBECipher(token, algorithm, mechanism);
                } else {
                    return new P11Cipher(token, algorithm, mechanism);
                }
            } else if (type == SIG) {
                if (algorithm.contains("RSASSA-PSS")) {
                    return new P11PSSSignature(token, algorithm, mechanism);
                } else {
                    return new P11Signature(token, algorithm, mechanism);
                }
            } else if (type == MAC) {
                return new P11Mac(token, algorithm, mechanism);
            } else if (type == KPG) {
                return new P11KeyPairGenerator(token, algorithm, mechanism);
            } else if (type == KA) {
                if (algorithm.equals("ECDH")) {
                    return new P11ECDHKeyAgreement(token, algorithm, mechanism);
                } else {
                    return new P11KeyAgreement(token, algorithm, mechanism);
                }
            } else if (type == KF) {
                return token.getKeyFactory(algorithm);
            } else if (type == SKF) {
                return new P11SecretKeyFactory(token, algorithm);
            } else if (type == KG) {
                // reference equality
                if (algorithm == "SunTlsRsaPremasterSecret") {
                    return new P11TlsRsaPremasterSecretGenerator(
                        token, algorithm, mechanism);
                } else if (algorithm == "SunTlsMasterSecret"
                        || algorithm == "SunTls12MasterSecret") {
                    return new P11TlsMasterSecretGenerator(
                        token, algorithm, mechanism);
                } else if (algorithm == "SunTlsKeyMaterial"
                        || algorithm == "SunTls12KeyMaterial") {
                    return new P11TlsKeyMaterialGenerator(
                        token, algorithm, mechanism);
                } else if (algorithm == "SunTlsPrf"
                        || algorithm == "SunTls12Prf") {
                    return new P11TlsPrfGenerator(token, algorithm, mechanism);
                } else {
                    return new P11KeyGenerator(token, algorithm, mechanism);
                }
            } else if (type == SR) {
                return token.getRandom();
            } else if (type == KS) {
                return token.getKeyStore();
            } else if (type == AGP) {
                if (algorithm == "EC") {
                    return new sun.security.util.ECParameters();
                } else if (algorithm == "GCM") {
                    return new sun.security.util.GCMParameters();
                } else if (algorithm == "ChaCha20-Poly1305") {
                    return new ChaCha20Poly1305Parameters(); // from SunJCE
                } else if (algorithm == "RSASSA-PSS") {
                    return new PSSParameters(); // from SunRsaSign
                } else if (algorithm == "DiffieHellman") {
                    return new DHParameters(); // from SunJCE
                } else {
                    throw new NoSuchAlgorithmException("Unsupported algorithm: "
                            + algorithm);
                }
            } else if (type == KDF) {
                try {
                    return new P11HKDF(token, algorithm, (KDFParameters) param);
                } catch (ClassCastException |
                         InvalidAlgorithmParameterException e) {
                    throw new NoSuchAlgorithmException(
                            "Cannot instantiate a service of KDF type.", e);
                }
            } else {
                throw new NoSuchAlgorithmException("Unknown type: " + type);
            }
        }

        public boolean supportsParameter(Object param) {
            if ((param == null) || (!token.isValid())) {
                return false;
            }
            if (!(param instanceof Key key)) {
                throw new InvalidParameterException("Parameter must be a Key");
            }
            String algorithm = getAlgorithm();
            String type = getType();
            String keyAlgorithm = key.getAlgorithm();
            // RSA signatures and cipher
            if (((type == CIP) && algorithm.startsWith("RSA"))
                    || (type == SIG) && (algorithm.contains("RSA"))) {
                if (!keyAlgorithm.equals("RSA")) {
                    return false;
                }
                return isLocalKey(key)
                        || (key instanceof RSAPrivateKey)
                        || (key instanceof RSAPublicKey);
            }
            // EC
            if (((type == KA) && algorithm.equals("ECDH"))
                    || ((type == SIG) && algorithm.contains("ECDSA"))) {
                if (!keyAlgorithm.equals("EC")) {
                    return false;
                }
                return isLocalKey(key)
                        || (key instanceof ECPrivateKey)
                        || (key instanceof ECPublicKey);
            }
            // DSA signatures
            if ((type == SIG) && algorithm.contains("DSA") &&
                    !algorithm.contains("ECDSA")) {
                if (!keyAlgorithm.equals("DSA")) {
                    return false;
                }
                return isLocalKey(key)
                        || (key instanceof DSAPrivateKey)
                        || (key instanceof DSAPublicKey);
            }
            // MACs and symmetric ciphers
            if ((type == CIP) || (type == MAC)) {
                // do not check algorithm name, mismatch is unlikely anyway
                return isLocalKey(key) || "RAW".equals(key.getFormat());
            }
            // DH key agreement
            if (type == KA) {
                if (!keyAlgorithm.equals("DH")) {
                    return false;
                }
                return isLocalKey(key)
                        || (key instanceof DHPrivateKey)
                        || (key instanceof DHPublicKey);
            }
            // should not reach here,
            // unknown engine type or algorithm
            throw new AssertionError
                ("SunPKCS11 error: " + type + ", " + algorithm);
        }

        private boolean isLocalKey(Key key) {
            return (key instanceof P11Key) && (((P11Key)key).token == token);
        }

        public String toString() {
            return super.toString() +
                " (" + Functions.getMechanismName(mechanism) + ")";
        }

    }

    /**
     * Log in to this provider.
     *
     * <p> If the token expects a PIN to be supplied by the caller,
     * the <code>handler</code> implementation must support
     * a <code>PasswordCallback</code>.
     *
     * <p> To determine if the token supports a protected authentication path,
     * the CK_TOKEN_INFO flag, CKF_PROTECTED_AUTHENTICATION_PATH, is consulted.
     *
     * @param subject this parameter is ignored
     * @param handler the <code>CallbackHandler</code> used by
     *  this provider to communicate with the caller
     *
     * @throws IllegalStateException if the provider requires configuration
     * and Provider.configure has not been called
     * @throws LoginException if the login operation fails
     */
    public void login(Subject subject, CallbackHandler handler)
        throws LoginException {

        if (!isConfigured()) {
            throw new IllegalStateException("Configuration is required");
        }

        if (!hasValidToken()) {
            throw new LoginException("No token present");

        }

        // see if a login is required
        if ((token.tokenInfo.flags & CKF_LOGIN_REQUIRED) == 0) {
            if (debug != null) {
                debug.println("login operation not required for token - " +
                                "ignoring login request");
            }
            return;
        }

        // see if user already logged in

        try {
            if (token.isLoggedInNow(null)) {
                // user already logged in
                if (debug != null) {
                    debug.println("user already logged in");
                }
                return;
            }
        } catch (PKCS11Exception e) {
            // ignore - fall thru and attempt login
        }

        // get the pin if necessary
        char[] pin = null;
        if ((token.tokenInfo.flags & CKF_PROTECTED_AUTHENTICATION_PATH) == 0) {

            // get password
            CallbackHandler myHandler = getCallbackHandler(handler);
            if (myHandler == null) {
                throw new LoginException
                        ("no password provided, and no callback handler " +
                        "available for retrieving password");
            }

            java.text.MessageFormat form = new java.text.MessageFormat
                        (ResourcesMgr.getString
                        ("PKCS11.Token.providerName.Password."));
            Object[] source = { getName() };

            PasswordCallback pcall = new PasswordCallback(form.format(source),
                                                        false);
            Callback[] callbacks = { pcall };

            try {
                myHandler.handle(callbacks);
            } catch (Exception e) {
                LoginException le = new LoginException
                        ("Unable to perform password callback");
                le.initCause(e);
                throw le;
            }

            pin = pcall.getPassword();
            pcall.clearPassword();
            if (pin == null) {
                if (debug != null) {
                    debug.println("caller passed NULL pin");
                }
            }
        }

        // perform token login
        Session session = null;
        try {
            session = token.getOpSession();
            // pin is NULL if using CKF_PROTECTED_AUTHENTICATION_PATH
            p11.C_Login(session.id(), CKU_USER, pin);

            if (debug != null) {
                debug.println("login succeeded");
            }
        } catch (PKCS11Exception pe) {
            if (pe.match(CKR_USER_ALREADY_LOGGED_IN)) {
                // let this one go
                if (debug != null) {
                    debug.println("user already logged in");
                }
                return;
            } else if (pe.match(CKR_PIN_INCORRECT)) {
                FailedLoginException fle = new FailedLoginException();
                fle.initCause(pe);
                throw fle;
            } else {
                LoginException le = new LoginException();
                le.initCause(pe);
                throw le;
            }
        } finally {
            token.releaseSession(session);
            if (pin != null) {
                Arrays.fill(pin, ' ');
            }
        }

        // we do not store the PIN in the subject for now
    }

    /**
     * Log out from this provider
     *
     * @throws IllegalStateException if the provider requires configuration
     * and Provider.configure has not been called
     * @throws LoginException if the logout operation fails
     */
    public void logout() throws LoginException {
        if (!isConfigured()) {
            throw new IllegalStateException("Configuration is required");
        }

        if (!hasValidToken()) {
            // app may call logout for cleanup, allow
            return;
        }

        if ((token.tokenInfo.flags & CKF_LOGIN_REQUIRED) == 0) {
            if (debug != null) {
                debug.println("logout operation not required for token - " +
                                "ignoring logout request");
            }
            return;
        }

        try {
            if (!token.isLoggedInNow(null)) {
                if (debug != null) {
                    debug.println("user not logged in");
                }
                if (config.getDestroyTokenAfterLogout()) {
                    token.destroy();
                }
                return;
            }
        } catch (PKCS11Exception e) {
            // ignore
        }

        // perform token logout
        Session session = null;
        try {
            session = token.getOpSession();
            p11.C_Logout(session.id());
            if (debug != null) {
                debug.println("logout succeeded");
            }
        } catch (PKCS11Exception pe) {
            if (pe.match(CKR_USER_NOT_LOGGED_IN)) {
                // let this one go
                if (debug != null) {
                    debug.println("user not logged in");
                }
                return;
            }
            LoginException le = new LoginException();
            le.initCause(pe);
            throw le;
        } finally {
            token.releaseSession(session);
            if (config.getDestroyTokenAfterLogout()) {
                token.destroy();
            }
        }
    }

    /**
     * Set a <code>CallbackHandler</code>
     *
     * <p> The provider uses this handler if one is not passed to the
     * <code>login</code> method.  The provider also uses this handler
     * if it invokes <code>login</code> on behalf of callers.
     * In either case if a handler is not set via this method,
     * the provider queries the
     * <i>auth.login.defaultCallbackHandler</i> security property
     * for the fully qualified class name of a default handler implementation.
     * If the security property is not set,
     * the provider is assumed to have alternative means
     * for obtaining authentication information.
     *
     * @param handler a <code>CallbackHandler</code> for obtaining
     *          authentication information, which may be <code>null</code>
     *
     * @throws IllegalStateException if the provider requires configuration
     * and Provider.configure has not been called
     */
    public void setCallbackHandler(CallbackHandler handler) {

        if (!isConfigured()) {
            throw new IllegalStateException("Configuration is required");
        }

        synchronized (LOCK_HANDLER) {
            pHandler = handler;
        }
    }

    private CallbackHandler getCallbackHandler(CallbackHandler handler) {

        // get default handler if necessary

        if (handler != null) {
            return handler;
        }

        if (debug != null) {
            debug.println("getting provider callback handler");
        }

        synchronized (LOCK_HANDLER) {
            // see if handler was set via setCallbackHandler
            if (pHandler != null) {
                return pHandler;
            }

            if (debug != null) {
                debug.println("getting default callback handler");
            }

            String defaultHandler = Security.getProperty
                    ("auth.login.defaultCallbackHandler");

            if (defaultHandler == null || defaultHandler.length() == 0) {

                // ok
                if (debug != null) {
                    debug.println("no default handler set");
                }
                return null;
            }

            try {
                Class<?> c = Class.forName
                           (defaultHandler,
                           true,
                           Thread.currentThread().getContextClassLoader());
                if (!CallbackHandler.class.isAssignableFrom(c)) {
                    // not the right subtype
                    if (debug != null) {
                        debug.println("default handler " + defaultHandler +
                                      " is not a CallbackHandler");
                    }
                    return null;
                }
                @SuppressWarnings("deprecation")
                Object result = c.newInstance();
                CallbackHandler myHandler = (CallbackHandler)result;
                // save it
                pHandler = myHandler;
                return myHandler;

            } catch (ReflectiveOperationException roe) {
                // ok
                if (debug != null) {
                    debug.println("Unable to load default callback handler");
                    roe.printStackTrace();
                }
            }
            return null;
        }
    }

    private Object writeReplace() throws ObjectStreamException {
        return new SunPKCS11Rep(this);
    }

    /**
     * Restores the state of this object from the stream.
     *
     * @param  stream the {@code ObjectInputStream} from which data is read
     * @throws IOException if an I/O error occurs
     * @throws ClassNotFoundException if a serialized class cannot be loaded
     */
    @java.io.Serial
    private void readObject(ObjectInputStream stream)
            throws IOException, ClassNotFoundException {
        throw new InvalidObjectException("SunPKCS11 not directly deserializable");
    }

    /**
     * Serialized representation of the SunPKCS11 provider.
     */
    private static class SunPKCS11Rep implements Serializable {

        static final long serialVersionUID = -2896606995897745419L;

        private final String providerName;

        private final String configName;

        SunPKCS11Rep(SunPKCS11 provider) throws NotSerializableException {
            providerName = provider.getName();
            configName = provider.config.getFileName();
            if (Security.getProvider(providerName) != provider) {
                throw new NotSerializableException("Only SunPKCS11 providers "
                    + "installed in java.security.Security can be serialized");
            }
        }

        private Object readResolve() throws ObjectStreamException {
            SunPKCS11 p = (SunPKCS11)Security.getProvider(providerName);
            if ((p == null) || (!p.config.getFileName().equals(configName))) {
                throw new NotSerializableException("Could not find "
                        + providerName + " in installed providers");
            }
            return p;
        }
    }
}
