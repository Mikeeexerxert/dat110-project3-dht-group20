package no.hvl.dat110.util;
/**
 * exercise/demo purpose in dat110
 * @author tdoy
 *
 */
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
public class Hash {
	public static BigInteger hashOf(String entity) {
		BigInteger hashInt;
		try {
			// we use MD5 with 128 bits digest
			MessageDigest md = MessageDigest.getInstance("MD5");
			// compute the hash of the input 'entity'
			byte[] digest = md.digest(entity.getBytes(StandardCharsets.UTF_8));
			// convert the hash into hex format
			String hashValue = toHex(digest);
			// convert the hex into BigInteger
			hashInt = new BigInteger(hashValue, 16);
		}
		catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
		return hashInt;
	}
	public static BigInteger addressSize() {
		BigInteger two	= BigInteger.valueOf(2L);
		int numOfBits = bitSize();
		return two.pow(numOfBits);
	}
	public static int bitSize() {
		int digestlen;
		try {
			// we use MD5 with 128 bits digest
			MessageDigest md = MessageDigest.getInstance("MD5");
			// find the digest length
			digestlen = md.getDigestLength();
		}
		catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
		return digestlen*8;
	}
	public static String toHex(byte[] digest) {
		StringBuilder strbuilder = new StringBuilder();
		for(byte b : digest) {
			strbuilder.append(String.format("%02x", b&0xff));
		}
		return strbuilder.toString();
	}
}
