package ocelot.classfile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import org.objectweb.asm.ClassReader;

/**
 *
 * @author ben
 */
public final class OcelotClassReader {

    private final byte[] clzBytes;
    private final String filename;

    private int major = 0;
    private int minor = 0;

    private int poolItemCount = 0;
    private int current = 0;
    private CPEntry[] items;

    private final static CPType[] table = new CPType[256];

    public OcelotClassReader(byte[] buf, String fName) {
        filename = fName;
        clzBytes = buf;
    }

    static {
        for (CPType cp : CPType.values()) {
            table[cp.getValue()] = cp;
        }
        // Sanity check
        int count = 0;
        for (int i = 0; i < 256; i++) {
            if (table[i] != null)
                count++;
        }
        final int numCPTypes = CPType.values().length;
        if (count != numCPTypes) {
            throw new IllegalStateException("Constant pool sanity check failed: " + count + " types found, should be " + numCPTypes);
        }
    }

    public void parseHeader() {
        if ((clzBytes[current++] != (byte) 0xca) || (clzBytes[current++] != (byte) 0xfe)
                || (clzBytes[current++] != (byte) 0xba) || (clzBytes[current++] != (byte) 0xbe)) {
            throw new IllegalArgumentException("Input file does not have correct magic number");
        }
        minor = ((int) clzBytes[current++] << 8) + (int) clzBytes[current++];
        major = ((int) clzBytes[current++] << 8) + (int) clzBytes[current++];
        poolItemCount = ((int) clzBytes[current++] << 8) + (int) clzBytes[current++];
    }

    void parseConstantPool() throws ClassNotFoundException {
        items = new CPEntry[poolItemCount - 1];
        for (int i = 0; i < poolItemCount - 1; i++) {
            int entry = clzBytes[current++] & 0xff;
            CPType tag = table[entry];
            if (tag == null) {
                throw new ClassNotFoundException("Unrecognised tag byte: " + entry + " encountered at position " + current + ". Stopping the parse.");
            }

            CPEntry item = null;
//            System.out.println("Tag seen: "+ tag);    
            // Create item based on tag
            switch (tag) {
                case UTF8: // String prefixed by a uint16 indicating the number of bytes in the encoded string which immediately follows
                    int len = ((int) clzBytes[current++] << 8) + (int) clzBytes[current++];
                    String str = new String(clzBytes, current, len, Charset.forName("UTF8"));
                    item = CPEntry.of(tag, str);
                    current += len;
                    break;
                case INTEGER: // Integer: a signed 32-bit two's complement number in big-endian format
                    int i2 = ((int) clzBytes[current++] << 24) + ((int) clzBytes[current++] << 16) + ((int) clzBytes[current++] << 8) + (int) clzBytes[current++];
                    item = CPEntry.of(tag, i2);
                    break;
                case FLOAT: // Float: a 32-bit single-precision IEEE 754 floating-point number
                    int i3 = ((int) clzBytes[current++] << 24) + ((int) clzBytes[current++] << 16) + ((int) clzBytes[current++] << 8) + (int) clzBytes[current++];
                    float f = Float.intBitsToFloat(i3);
                    item = CPEntry.of(tag, f);
                    break;
                case LONG: // Long: a signed 64-bit two's complement number in big-endian format (takes two slots in the constant pool table)
                    int i4 = ((int) clzBytes[current++] << 24) + ((int) clzBytes[current++] << 16) + ((int) clzBytes[current++] << 8) + (int) clzBytes[current++];
                    int i5 = ((int) clzBytes[current++] << 24) + ((int) clzBytes[current++] << 16) + ((int) clzBytes[current++] << 8) + (int) clzBytes[current++];
                    long l = ((long) i4 << 32) + (long) i5;
                    item = CPEntry.of(tag, l);
                    break;
                case DOUBLE: // Double: a 64-bit double-precision IEEE 754 floating-point number (takes two slots in the constant pool table)
                    // FIXME
                    item = CPEntry.of(tag, 3.14);
                    current += 8;
                    break;
                case CLASS: // Class reference: an uint16 within the constant pool to a UTF-8 string containing the fully qualified class name
                    int ref = ((int) clzBytes[current++] << 8) + (int) clzBytes[current++];
                    item = CPEntry.of(tag, new Ref(ref));
                    break;
                case STRING: // String reference: an uint16 within the constant pool to a UTF-8 string
                    int ref2 = ((int) clzBytes[current++] << 8) + (int) clzBytes[current++];
                    item = CPEntry.of(tag, new Ref(ref2));
                    break;
                case FIELDREF: // Field reference: two uint16 within the pool, 1st pointing to a Class reference, 2nd to a Name and Type descriptor
				/* FALL THROUGH TO METHODREF */
                case METHODREF: // Method reference: two uint16s within the pool, 1st pointing to a Class reference, 2nd to a Name and Type descriptor
				/* FALL THROUGH TO INTERFACE_METHODREF */
                case INTERFACE_METHODREF: // Interface method reference: 2 uint16 within the pool, 1st pointing to a Class reference, 2nd to a Name and Type descriptor
                    int cRef = ((int) clzBytes[current++] << 8) + (int) clzBytes[current++];
                    int nameAndTypeRef = ((int) clzBytes[current++] << 8) + (int) clzBytes[current++];
                    item = CPEntry.of(tag, new Ref(cRef), new Ref(nameAndTypeRef));
                    break;
                case NAMEANDTYPE: // Name and type descriptor: 2 uint16 to UTF-8 strings, 1st representing a name (identifier), 2nd a specially encoded type descriptor
                    int nameRef = ((int) clzBytes[current++] << 8) + (int) clzBytes[current++];
                    int typeRef = ((int) clzBytes[current++] << 8) + (int) clzBytes[current++];
                    item = CPEntry.of(tag, new Ref(nameRef), new Ref(typeRef));
                    break;
                default:
                    throw new ClassNotFoundException("Reached impossible Constant Pool Tag.");
            }
            items[i] = item;
        }
    }

    public byte[] getClzBytes() {
        return clzBytes;
    }

    public int getMajor() {
        return major;
    }

    public int getMinor() {
        return minor;
    }

    public int getPoolItemCount() {
        return poolItemCount;
    }

    public CPEntry getCPEntry(int i) {
        return items[i - 1]; // CP is 1-based
    }

    public String resolveAsString(int i) {
        final CPEntry top = items[i - 1];

        CPEntry other = null;
        int left, right = 0;
        switch (top.getType()) {
            case UTF8:
                return top.getStr();
            case INTEGER:
                return "" + top.getNum().intValue();
            case FLOAT:
                return "" + top.getNum().floatValue();
            case LONG:
                return "" + top.getNum().longValue();
            case DOUBLE:
                return "" + top.getNum().doubleValue();
            case CLASS:
            case STRING:
                other = items[top.getRef().getOther() - 1];
                // Verification - could check type is STRING here
                return other.getStr();
            case FIELDREF:
            case METHODREF:
            case INTERFACE_METHODREF:
            case NAMEANDTYPE:
                left = top.getRef().getOther();
                right = top.getRef2().getOther();
                return resolveAsString(left) + top.getType().separator() + resolveAsString(right);
            default:
                throw new RuntimeException("Reached impossible Constant Pool Tag: " + top);
        }
    }

    @Override
    public String toString() {
        return "ClassEntry{" + "file_name=" + filename + ", major=" + major + ", minor=" + minor + ", poolItems=" + poolItemCount + '}';
    }

    // Maybe some useful techniques in ASM ?
    public OcelotClassReader scan(String cName) throws IOException {
        final Path clzPath = classNameToPath(cName);
        final byte[] buf = Files.readAllBytes(clzPath);
        OcelotClassReader out = null;
        try (final InputStream in = Files.newInputStream(clzPath)) {
            try {
                final ClassReader cr = new ClassReader(in);
                out = new OcelotClassReader(buf, clzPath.toString());
//                out.init(cr);
            } catch (Exception e) {
                throw new IOException("Could not read class file " + clzPath, e);
            }
        }
        return out;
    }

    public static Path classNameToPath(String classname) {
        return null;
    }

}