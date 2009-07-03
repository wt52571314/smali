/*
 * [The "BSD licence"]
 * Copyright (c) 2009 Ben Gruver
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.jf.dexlib;

import org.jf.dexlib.Util.AnnotatedOutput;
import org.jf.dexlib.Util.ByteArrayInput;
import org.jf.dexlib.Util.FileUtils;
import org.jf.dexlib.Util.Input;

import java.io.File;
import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.zip.Adler32;

    /**
     * <h3>Main use cases</h3>
     *
     * <p>These are the main use cases that drove the design of this library</p>
     *
     * <ol>
     * <li><p><b>Annotate an existing dex file</b> - In this case, the intent is to document the structure of
     *    an existing dex file. We want to be able to read in the dex file, and then write out a dex file
     *    that is exactly the same (while adding annotation information to an AnnotatedOutput object)</p></li>
     *    
     * <li><p><b>Canonicalize an existing dex file</b> - In this case, the intent is to rewrite an existing dex file
     *    so that it is in a canonical form. There is a certain amount of leeway in how various types of
     *    tems in a dex file are ordered or represented. It is sometimes useful to be able to easily
     *    compare a disassebled and reassembled dex file with the original dex file. If both dex-files are
     *    written canonically, they "should" match exactly, barring any explicit changes to the reassembled
     *    file.</p>
     *
     *    <p>Currently, there are a couple of pieces of information that probably won't match exactly
     *    <ul>
     *    <li>the order of exception handlers in the <code>EncodedCatchHandlerList</code> for a method</li>
     *    <li>the ordering of some of the debug info in the <code>{@link DebugInfoItem}</code> for a method</li>
     *    </ul></p>
     *
     * 
     *    <p>Note that the above discrepancies should typically only be "intra-item" differences. They
     *    shouldn't change the size of the item, or affect how anything else is placed or laid out</p></li>
     *
     * <li><p><b>Creating a dex file from scratch</b> - In this case, a blank dex file is created and then classes
     *    are added to it incrementally by calling the {@link Section#intern intern} method of
     *    {@link DexFile#ClassDefsSection}, which will add all the information necessary to represent the given
     *    class. For example, when assembling a dex file from a set of assembly text files.</p>
     *
     *    <p>In this case, we can choose to write  the dex file in a canonical form or not. It is somewhat
     *    slower to write it in a canonical format, due to the extra sorting and calculations that are
     *    required.</p></li>
     *
     *
     * <li><p><b>Reading in the dex file</b> - In this case, the intent is to read in a dex file and expose all the
     *    data to the calling application. For example, when disassembling a dex file into a text based
     *    assembly format, or doing other misc processing of the dex file.</p></li>
     *
     *
     * <h3>Other use cases</h3>
     *
     * <p>These are other use cases that are possible, but did not drive the design of the library.
     * No effort was made to test these use cases or ensure that they work. Some of these could
     * probably be better achieved with a disassemble - modify - reassemble type process, using
     * smali/baksmali or another assembler/disassembler pair that are compatible with each other</p>
     *
     * <ul>
     * <li>deleting classes/methods/etc. from a dex file</li>
     * <li>merging 2 dex files</li>
     * <li>splitting a dex file</li>
     * <li>moving classes from 1 dex file to another</li>
     * <li>removing the debug information from a dex file</li>
     * <li>obfustication of a dex file</li>
     * </ul>
     */
public class DexFile
{
    /**
     * A mapping from ItemType to the section that contains items of the given type
     */
    private final HashMap<ItemType, Section> sectionsByType;

    /**
     * Ordered lists of the indexed and offsetted sections. The order of these lists specifies the order
     * that the sections will be written in
     */
    private final IndexedSection[] indexedSections;
    private final OffsettedSection[] offsettedSections;

    /**
     * dalvik had a bug where it wrote the registers for certain types of debug info in a signed leb
     * format, instead of an unsigned leb format. There are no negative registers of course, but
     * certain positive values have a different encoding depending on whether they are encoded as
     * an unsigned leb128 or a signed leb128. Specifically, the signed leb128 is 1 byte longer in some cases.
     *
     * This determine whether we should keep any signed registers as signed, or force all register to
     * unsigned. By default we don't keep track of whether they were signed or not, and write them back
     * out as unsigned. This option only has an effect when reading an existing dex file. It has no
     * effect when a dex file is created from scratch
     *
     * The 2 main use-cases in play are
     * 1. Annotate an existing dex file - In this case, preserveSignedRegisters should be false, so that we keep
     * track of any signed registers and write them back out as signed Leb128 values.
     *
     * 2. Canonicalize an existing dex file - In this case, fixRegisters should be true, so that all
     * registers in the debug info are written as unsigned Leb128 values regardless of how they were
     * originally encoded 
     */
    private final boolean preserveSignedRegisters;

    /**
     * When true, this prevents any sorting of the items during placement of the dex file. This
     * should *only* be set to true when this dex file was read in from an existing (valid) dex file,
     * and no modifications were made (i.e. no items added or deleted). Otherwise it is likely that
     * an invalid dex file will be generated.
     *
     * This is useful for the first use case (annotating an existing dex file). This ensures the items
     * retain the same order as in the original dex file.
     */
    private boolean inplace = false;

    /**
     * When true, this imposes an full ordering on all the items, to force them into a (possibly
     * arbitrary) canonical order. When false, only the items that the dex format specifies
     * an order for are sorted. The rest of the items are not ordered.
     *
     * This is useful for the second use case (canonicalizing an existing dex file) or possibly for
     * the third use case (creating a dex file from scratch), if there is a need to write the new
     * dex file in a canonical form. 
     */
    private boolean sortAllItems = false;


    /**
     * this is used to access the dex file from within inner classes, when they declare fields or
     * variable that hide fields on this object
     */
    private final DexFile dexFile = this;


    /**
     * A private constructor containing common code to initialize the section maps and lists
     * @param preserveSignedRegisters If true, keep track of any registers in the debug information
     * that are signed, so they will be written in the same format. See
     * <code>getPreserveSignedRegisters()</code>
     */
    private DexFile(boolean preserveSignedRegisters) {
        this.preserveSignedRegisters = preserveSignedRegisters;

        sectionsByType = new HashMap<ItemType, Section>(18);

        sectionsByType.put(ItemType.TYPE_ANNOTATION_ITEM, AnnotationsSection);
        sectionsByType.put(ItemType.TYPE_ANNOTATION_SET_ITEM, AnnotationSetsSection);
        sectionsByType.put(ItemType.TYPE_ANNOTATION_SET_REF_LIST, AnnotationSetRefListsSection);
        sectionsByType.put(ItemType.TYPE_ANNOTATIONS_DIRECTORY_ITEM, AnnotationDirectoriesSection);
        sectionsByType.put(ItemType.TYPE_CLASS_DATA_ITEM, ClassDataSection);
        sectionsByType.put(ItemType.TYPE_CLASS_DEF_ITEM, ClassDefsSection);
        sectionsByType.put(ItemType.TYPE_CODE_ITEM, CodeItemsSection);
        sectionsByType.put(ItemType.TYPE_DEBUG_INFO_ITEM, DebugInfoItemsSection);
        sectionsByType.put(ItemType.TYPE_ENCODED_ARRAY_ITEM, EncodedArraysSection);
        sectionsByType.put(ItemType.TYPE_FIELD_ID_ITEM, FieldIdsSection);
        sectionsByType.put(ItemType.TYPE_HEADER_ITEM, HeaderItemSection);
        sectionsByType.put(ItemType.TYPE_MAP_LIST, MapSection);
        sectionsByType.put(ItemType.TYPE_METHOD_ID_ITEM, MethodIdsSection);
        sectionsByType.put(ItemType.TYPE_PROTO_ID_ITEM, ProtoIdsSection);
        sectionsByType.put(ItemType.TYPE_STRING_DATA_ITEM, StringDataSection);
        sectionsByType.put(ItemType.TYPE_STRING_ID_ITEM, StringIdsSection);
        sectionsByType.put(ItemType.TYPE_TYPE_ID_ITEM, TypeIdsSection);
        sectionsByType.put(ItemType.TYPE_TYPE_LIST, TypeListsSection);

        indexedSections = new IndexedSection[] {
                StringIdsSection,
                TypeIdsSection,
                ProtoIdsSection,
                FieldIdsSection,
                MethodIdsSection,
                ClassDefsSection
        };

        offsettedSections = new OffsettedSection[] {
                AnnotationSetRefListsSection,
                AnnotationSetsSection,
                CodeItemsSection,
                AnnotationDirectoriesSection,
                TypeListsSection,
                StringDataSection,
                AnnotationsSection,
                EncodedArraysSection,
                ClassDataSection,
                DebugInfoItemsSection
        };
    }


    /**
     * Construct a new DexFile instance by reading in the given dex file.
     * @param file The dex file to read in
     */
    public DexFile(String file) {
        this(new File(file), true);
    }

    /**
     * Construct a new DexFile instance by reading in the given dex file,
     * and optionally keep track of any registers in the debug information that are signed,
     * so they will be written in the same format.
     * @param file The dex file to read in
     * @param preserveSignedRegisters If true, keep track of any registers in the debug information
     * that are signed, so they will be written in the same format. See
     * <code>getPreserveSignedRegisters()</code>
     */
    public DexFile(String file, boolean preserveSignedRegisters) {
        this(new File(file), preserveSignedRegisters);
    }

    /**
     * Construct a new DexFile instead by reading in the given dex file.
     * @param file The dex file to read in
     */
    public DexFile(File file) {
        this(file, true);
    }

    /**
     * Construct a new DexFile instance by reading in the given dex file,
     * and optionally keep track of any registers in the debug information that are signed,
     * so they will be written in the same format.
     * @param file The dex file to read in
     * @param preserveSignedRegisters If true, keep track of any registers in the debug information
     * that are signed, so they will be written in the same format.
     * @see #getPreserveSignedRegisters
     */
    public DexFile(File file, boolean preserveSignedRegisters) {
        this(preserveSignedRegisters);

        Input in = new ByteArrayInput(FileUtils.readFile(file));

        HeaderItemSection.readFrom(1, in);
        HeaderItem headerItem = HeaderItemSection.items.get(0);

        in.setCursor(headerItem.getMapOffset());
        MapSection.readFrom(1, in);

        MapField[] mapEntries = MapSection.items.get(0).getMapEntries();
        HashMap<Integer, MapField> mapMap = new HashMap<Integer, MapField>();
        for (MapField mapField: mapEntries) {
            mapMap.put(mapField.getSectionItemType().getMapValue(), mapField);    
        }

        /**
         * This defines the order in which the sections are read in. This is not
         * necessarily the order in which the appear in the file.
         */
        //TODO: do we *need* to read them in a specific order, rather than the order that is in the file?
        int[] sectionTypes = new int[] {
            ItemType.TYPE_HEADER_ITEM.getMapValue(),
            ItemType.TYPE_STRING_ID_ITEM.getMapValue(),
            ItemType.TYPE_TYPE_ID_ITEM.getMapValue(),
            ItemType.TYPE_PROTO_ID_ITEM.getMapValue(),
            ItemType.TYPE_FIELD_ID_ITEM.getMapValue(),
            ItemType.TYPE_METHOD_ID_ITEM.getMapValue(),
            ItemType.TYPE_CLASS_DEF_ITEM.getMapValue(),
            ItemType.TYPE_STRING_DATA_ITEM.getMapValue(),
            ItemType.TYPE_ENCODED_ARRAY_ITEM.getMapValue(),
            ItemType.TYPE_ANNOTATION_ITEM.getMapValue(),
            ItemType.TYPE_ANNOTATION_SET_ITEM.getMapValue(),
            ItemType.TYPE_ANNOTATION_SET_REF_LIST.getMapValue(),
            ItemType.TYPE_ANNOTATIONS_DIRECTORY_ITEM.getMapValue(),
            ItemType.TYPE_TYPE_LIST.getMapValue(),
            ItemType.TYPE_CODE_ITEM.getMapValue(),
            ItemType.TYPE_CLASS_DATA_ITEM.getMapValue(),
            ItemType.TYPE_DEBUG_INFO_ITEM.getMapValue(),
            ItemType.TYPE_MAP_LIST.getMapValue()
        };

        for (int sectionType: sectionTypes) {
            MapField mapField = mapMap.get(sectionType);
            if (mapField != null) {
                Section section = sectionsByType.get(mapField.getSectionItemType());
                if (section != null) {
                    in.setCursor(mapField.getCachedSectionOffset());
                    section.readFrom(mapField.getCachedSectionSize(), in);
                }
            }
        }
    }

    /**
     * Constructs a new, blank dex file. Classes can be added to this dex file by calling
     * the <code>Section.intern()</code> method of <code>ClassDefsSection</code>
     */
    public DexFile() {
        this(true);

        HeaderItemSection.intern(new HeaderItem(dexFile, 0));
        MapSection.intern(new MapItem(dexFile));
    }

    /**
     * Convenience method to retrieve the header item
     * @return the header item
     */
    public HeaderItem getHeaderItem() {
        return HeaderItemSection.getItems().get(0);
    }

    /**
     * Convenience method to retrieve the map item
     * @return the map item
     */
    public MapItem getMapItem() {
        return MapSection.getItems().get(0);
    }

    /**
     * Get the <code>Section</code> containing items of the same type as the given item
     * @param item Get the <code>Section</code> that contains items of this type
     * @param <T> The specific item subclass - inferred from the passed item
     * @return the <code>Section</code> containing items of the same type as the given item
     */
    public <T extends Item> Section<T> getSectionForItem(T item) {
        return sectionsByType.get(item.getItemType());
    }

    /**
     * Get the <code>Section</code> containing items of the given type
     * @param itemType the type of item
     * @return the <code>Section</code> containing items of the given type
     */
    public Section getSectionForType(ItemType itemType) {
        return sectionsByType.get(itemType);
    }

    /**
     * Get a boolean value indicating whether this dex file preserved any signed
     * registers in the debug info as it read the dex file in. By default, the dex file
     * doesn't check whether the registers are encoded as unsigned or signed values.
     *
     * This does *not* affect the actual register value that is read in. The value is
     * read correctly regardless
     *
     * This does affect whether any signed registers will retain the same encoding or be
     * forced to the (correct) unsigned encoding when the dex file is written back out.
     *
     * See the discussion about signed register values in the documentation for
     * <code>DexFile</code>
     * @return a boolean indicating whether this dex file preserved any signed registers
     * as it was read in
     */
    public boolean getPreserveSignedRegisters() {
        return preserveSignedRegisters;
    }

    /**
     * Get a boolean value indicating whether all items should be placed into a
     * (possibly arbitrary) "canonical" ordering. If false, then only the items
     * that must be ordered per the dex specification are sorted.
     *
     * When true, writing the dex file involves somewhat more overhead
     *
     * If both SortAllItems and Inplace are true, Inplace takes precedence
     * @return a boolean value indicating whether all items should be sorted 
     */
    public boolean getSortAllItems() {
        return this.sortAllItems;
    }

    /**
     * Set a boolean value indicating whether all items should be placed into a
     * (possibly arbitrary) "canonical" ordering. If false, then only the items
     * that must be ordered per the dex specification are sorted.
     *
     * When true, writing the dex file involves somewhat more overhead
     *
     * If both SortAllItems and Inplace are true, Inplace takes precedence
     * @param value a boolean value indicating whether all items should be sorted
     */
    public void setSortAllItems(boolean value) {
        this.sortAllItems = value;
    }

    /**
     * Get a boolean value indicating whether items in this dex file should be
     * written back out "in-place", or whether the normal layout logic should be
     * applied.
     *
     * This should only be used for a dex file that has been read from an existing
     * dex file, and no modifications have been made to the dex file. Otherwise,
     * there is a good chance that the resulting dex file will be invalid due to
     * items that aren't placed correctly
     *
     * If both SortAllItems and Inplace are true, Inplace takes precedence          
     * @return a boolean value indicating whether items in this dex file should be
     * written back out in-place.
     */
    public boolean getInplace() {
        return this.inplace;
    }

    /**
     * Set a boolean value indicating whether items in this dex file should be
     * written back out "in-place", or whether the normal layout logic should be
     * applied.
     *
     * This should only be used for a dex file that has been read from an existing
     * dex file, and no modifications have been made to the dex file. Otherwise,
     * there is a good chance that the resulting dex file will be invalid due to
     * items that aren't placed correctly
     *
     * If both SortAllItems and Inplace are true, Inplace takes precedence
     * @param value a boolean value indicating whether items in this dex file should be
     * written back out in-place.
     */
    public void setInplace(boolean value) {
        this.inplace = value;
    }

    /**
     * This method should be called before writing a dex file. It sorts the sections
     * as needed or as indicated by <code>getSortAllItems()</code> and <code>getInplace()</code>,
     * and then performs a pass through all of the items, finalizing the position (i.e.
     * index and/or offset) of each item in the dex file.
     *
     * This step is needed primarily so that the indexes and offsets of all indexed and
     * offsetted items are available when writing references to those items elsewhere.
     */
    public void place() {
        HeaderItem headerItem = getHeaderItem();

        int offset = HeaderItemSection.place(0);

        int dataOffset;

        for (IndexedSection indexedSection: indexedSections) {
            offset = indexedSection.place(offset);
        }

        dataOffset = offset;
        headerItem.setDataOffset(dataOffset);

        //TODO: if inplace is true, we need to use the order of the sections as they were in the original file
        for (OffsettedSection offsettedSection: offsettedSections) {
            if (this.sortAllItems && !this.inplace) {
                offsettedSection.sortSection();
            }
            offset = offsettedSection.place(offset);
        }

        offset = MapSection.place(offset);


        headerItem.setFileSize(offset);
        headerItem.setDataSize(offset - dataOffset);
    }

    /**
     * Writes the dex file to the give <code>AnnotatedOutput</code> object. If
     * <code>out.Annotates()</code> is true, then annotations that document the format
     * of the dex file are written.
     *
     * You must call <code>place()</code> on this dex file, before calling this method
     * @param out the AnnotatedOutput object to write the dex file and annotations to
     *
     * After calling this method, you should call <code>calcSignature()</code> and
     * then <code>calcChecksum()</code> on the resulting byte array, to calculate the
     * signature and checksum in the header
     */
    public void writeTo(AnnotatedOutput out) {
        HeaderItemSection.writeTo(out);

        for (IndexedSection indexedSection: indexedSections) {
            indexedSection.writeTo(out);
        }

        for (OffsettedSection offsettedSection: offsettedSections) {
            offsettedSection.writeTo(out);
        }

        MapSection.writeTo(out);
    }

    /**
     * The <code>IndexedSection</code> containing the sole <code>HeaderItem</code> item. Use
     * <code>getHeaderItem()</code> instead.
     */
    public final IndexedSection<HeaderItem> HeaderItemSection = new IndexedSection<HeaderItem>(this) {
        protected HeaderItem make(int index) {
            return new HeaderItem(dexFile, index);
        }
    };

    /**
     * The <code>IndexedSection</code> containing <code>StringIdItem</code> items
     */
    public final IndexedSection<StringIdItem> StringIdsSection = new IndexedSection<StringIdItem>(this) {
        protected StringIdItem make(int index) {
            return new StringIdItem(dexFile, index);
        }
    };

    /**
     * The <code>IndexedSection</code> containing <code>TypeIdItem</code> items
     */
    public final IndexedSection<TypeIdItem> TypeIdsSection = new IndexedSection<TypeIdItem>(this) {
        protected TypeIdItem make(int index) {
            return new TypeIdItem(dexFile, index);
        }
    };

    /**
     * The <code>IndexedSection</code> containing <code>ProtoIdItem</code> items
     */
    public final IndexedSection<ProtoIdItem> ProtoIdsSection = new IndexedSection<ProtoIdItem>(this) {
        protected ProtoIdItem make(int index) {
            return new ProtoIdItem(dexFile, index);
        }
    };

    /**
     * The <code>IndexedSection</code> containing <code>FieldIdItem</code> items
     */
    public final IndexedSection<FieldIdItem> FieldIdsSection = new IndexedSection<FieldIdItem>(this) {
        protected FieldIdItem make(int index) {
            return new FieldIdItem(dexFile, index);
        }
    };

    /**
     * The <code>IndexedSection</code> containing <code>MethodIdItem</code> items
     */
    public final IndexedSection<MethodIdItem> MethodIdsSection = new IndexedSection<MethodIdItem>(this) {
        protected MethodIdItem make(int index) {
            return new MethodIdItem(dexFile, index);
        }
    };

    /**
     * The <code>IndexedSection</code> containing <code>ClassDefItem</code> items
     */
    public final IndexedSection<ClassDefItem> ClassDefsSection = new IndexedSection<ClassDefItem>(this) {
        protected ClassDefItem make(int index) {
            return new ClassDefItem(dexFile, index);
        }

        public int place(int offset) {
            if (dexFile.getInplace()) {
                return super.place(offset);
            }
            
            int ret = ClassDefItem.placeClassDefItems(this, offset);

            this.offset = items.get(0).getOffset();
            return ret;
        }
    };

    /**
     * The <code>IndexedSection</code> containing the sole <code>MapItem</code>. Use
     * <code>getMapItem()</code> instead
     */
    public final IndexedSection<MapItem> MapSection = new IndexedSection<MapItem>(this) {
        protected MapItem make(int index) {
            return new MapItem(dexFile, index);
        }

        public MapItem intern(MapItem item) {
            this.items.add(item);
            return item;
        }
    };
    
    /**
     * The <code>OffsettedSection</code> containing <code>TypeListItem</code> items
     */
    public final OffsettedSection<TypeListItem> TypeListsSection = new OffsettedSection<TypeListItem>(this) {
        protected TypeListItem make(int offset) {
            return new TypeListItem(dexFile, offset);
        }
    };
    
    /**
     * The <code>OffsettedSection</code> containing <code>AnnotationSetRefList</code> items
     */
    public final OffsettedSection<AnnotationSetRefList> AnnotationSetRefListsSection =
            new OffsettedSection<AnnotationSetRefList>(this) {
                protected AnnotationSetRefList make(int offset) {
                    return new AnnotationSetRefList(dexFile, offset);
                }
            };
    
    /**
     * The <code>OffsettedSection</code> containing <code>AnnotationSetItem</code> items
     */
    public final OffsettedSection<AnnotationSetItem> AnnotationSetsSection =
            new OffsettedSection<AnnotationSetItem>(this) {
                protected AnnotationSetItem make(int offset) {
                    return new AnnotationSetItem(dexFile, offset);
                }
            };

    /**
     * The <code>OffsettedSection</code> containing <code>ClassDataItem</code> items
     */
    public final OffsettedSection<ClassDataItem> ClassDataSection = new OffsettedSection<ClassDataItem>(this) {
        protected ClassDataItem make(int offset) {
            return new ClassDataItem(dexFile, offset);
        }
    };

    /**
     * The <code>OffsettedSection</code> containing <code>CodeItem</code> items
     */
    public final OffsettedSection<CodeItem> CodeItemsSection = new OffsettedSection<CodeItem>(this) {
        protected CodeItem make(int offset) {
            return new CodeItem(dexFile, offset);
        }
    };

    /**
     * The <code>OffsettedSection</code> containing <code>StringDataItem</code> items
     */
    public final OffsettedSection<StringDataItem> StringDataSection = new OffsettedSection<StringDataItem>(this) {
        protected StringDataItem make(int offset) {
            return new StringDataItem(dexFile, offset);
        }
    };

    /**
     * The <code>OffsettedSection</code> containing <code>DebugInfoItem</code> items
     */
    public final OffsettedSection<DebugInfoItem> DebugInfoItemsSection = new OffsettedSection<DebugInfoItem>(this) {
        protected DebugInfoItem make(int offset) {
            return new DebugInfoItem(dexFile, offset);
        }
    };

    /**
     * The <code>OffsettedSection</code> containing <code>AnnotationItem</code> items
     */
    public final OffsettedSection<AnnotationItem> AnnotationsSection = new OffsettedSection<AnnotationItem>(this) {
        protected AnnotationItem make(int offset) {
            return new AnnotationItem(dexFile, offset);
        }
    };

    /**
     * The <code>OffsettedSection</code> containing <code>EncodedArrayItem</code> items
     */
    public final OffsettedSection<EncodedArrayItem> EncodedArraysSection = new OffsettedSection<EncodedArrayItem>(this) {
        protected EncodedArrayItem make(int offset) {
            return new EncodedArrayItem(dexFile, offset);
        }
    };
    
    /**
     * The <code>OffsettedSection</code> containing <code>AnnotationDirectoryItem</code> items
     */
    public final OffsettedSection<AnnotationDirectoryItem> AnnotationDirectoriesSection =
            new OffsettedSection<AnnotationDirectoryItem>(this) {
                protected AnnotationDirectoryItem make(int offset) {
                    return new AnnotationDirectoryItem(dexFile, offset);
                }
            };


    /**
     * Calculates the signature for the dex file in the given byte array,
     * and then writes the signature to the appropriate location in the header
     * containing in the array
     *
     * @param bytes non-null; the bytes of the file
     */
    public static void calcSignature(byte[] bytes) {
        MessageDigest md;

        try {
            md = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException ex) {
            throw new RuntimeException(ex);
        }

        md.update(bytes, 32, bytes.length - 32);

        try {
            int amt = md.digest(bytes, 12, 20);
            if (amt != 20) {
                throw new RuntimeException("unexpected digest write: " + amt +
                                           " bytes");
            }
        } catch (DigestException ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * Calculates the checksum for the <code>.dex</code> file in the
     * given array, and modify the array to contain it.
     *
     * @param bytes non-null; the bytes of the file
     */
    public static void calcChecksum(byte[] bytes) {
        Adler32 a32 = new Adler32();

        a32.update(bytes, 12, bytes.length - 12);

        int sum = (int) a32.getValue();

        bytes[8]  = (byte) sum;
        bytes[9]  = (byte) (sum >> 8);
        bytes[10] = (byte) (sum >> 16);
        bytes[11] = (byte) (sum >> 24);
    }
}
