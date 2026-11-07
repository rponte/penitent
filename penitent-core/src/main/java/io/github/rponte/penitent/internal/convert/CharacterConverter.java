package io.github.rponte.penitent.internal.convert;

final class CharacterConverter implements BuiltInConverter<Character> {

	@Override
	public Object toDatabase(Character value) {
		return String.valueOf(value);
	}

	/** Exactly one character: anything longer would be cut without an error. */
	@Override
	public Character fromDatabase(Object raw) {
		String text = new StringConverter().fromDatabase(raw);
		if (text.length() != 1) {
			throw new ScalarCodec.ConversionException("'" + text + "' does not fit in a char: it has "
					+ text.length() + " characters");
		}
		return text.charAt(0);
	}
}
