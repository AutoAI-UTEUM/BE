package io.edupilot.deletion;

import static org.assertj.core.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.edupilot.material.storage.*;

class DeletionStorageTest {
 @TempDir Path root;
 LocalVolumeStorage storage(){return new LocalVolumeStorage(new StorageProperties(root));}
 @Test void cleanupDeletesOnlySelectedMaterialsImagesAndIsIdempotent() throws Exception {
  var files=storage();String selected=files.store(new ByteArrayInputStream("%PDF-synthetic".getBytes()));
  String other=files.store(new ByteArrayInputStream("%PDF-other".getBytes()));
  String image=selected.replace(".pdf","-pages/1.jpg"), otherImage=other.replace(".pdf","-pages/1.jpg");
  files.storePageImage(new ByteArrayInputStream(new byte[]{1}),image);
  files.storePageImage(new ByteArrayInputStream(new byte[]{2}),otherImage);
  files.deleteMaterialRenders(selected);files.deleteMaterialRenders(selected);files.delete(selected);files.delete(selected);
  assertThat(Files.exists(root.resolve(image))).isFalse();assertThat(Files.exists(root.resolve(selected))).isFalse();
  assertThat(Files.exists(root.resolve(otherImage))).isTrue();assertThat(Files.exists(root.resolve(other))).isTrue();
 }
 @Test void unexpectedContentIsPreservedBeforeAnyImageIsDeleted() throws Exception {
  var files=storage();String key="materials/"+UUID.randomUUID()+".pdf", image=key.replace(".pdf","-pages/1.jpg");
  files.storePageImage(new ByteArrayInputStream(new byte[]{1}),image);
  Path unrelated=root.resolve(image).resolveSibling("unrelated.txt");Files.writeString(unrelated,"Synthetic unrelated content");
  assertThatThrownBy(()->files.deleteMaterialRenders(key)).isInstanceOf(StorageException.class);
  assertThat(Files.exists(root.resolve(image))).isTrue();assertThat(Files.exists(unrelated)).isTrue();
 }
 @Test void invalidKeysCannotDeleteAnotherDirectory() {
  for(String key:new String[]{"../secret.pdf","materials/../../secret.pdf","avatars/"+UUID.randomUUID()+".png","materials/not-a-uuid.pdf"})
   assertThatThrownBy(()->storage().deleteMaterialRenders(key)).isInstanceOf(StorageException.class);
 }
 @Test void directoryContentIsNeverRecursivelyRemoved() throws Exception {
  String key="materials/"+UUID.randomUUID()+".pdf";
  Path nested=root.resolve(key.replace(".pdf","-pages/nested"));Files.createDirectories(nested);Files.writeString(nested.resolve("kept.txt"),"Synthetic");
  assertThatThrownBy(()->storage().deleteMaterialRenders(key)).isInstanceOf(StorageException.class);
  assertThat(Files.exists(nested.resolve("kept.txt"))).isTrue();
 }
 @Test void defaultPropertiesDoNotPermitAnyPhysicalDeletion() {
  var policy=new DeletionProperties(false,null,null,null,null,null,java.time.Duration.ofMinutes(2),java.time.Duration.ofMinutes(1),5,25);
  for(var kind:DeletionKind.values())assertThat(policy.permits(kind)).isFalse();
 }
}
