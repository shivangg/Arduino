/*
 * This file is part of Arduino.
 *
 * Copyright 2014 Arduino LLC (http://www.arduino.cc/)
 *
 * Arduino is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 *
 * As a special exception, you may use this file as part of a free software
 * library without restriction.  Specifically, if other files instantiate
 * templates or use macros or inline functions from this file, or you compile
 * this file and link it with other files to produce an executable, this
 * file does not by itself cause the resulting executable to be covered by
 * the GNU General Public License.  This exception does not however
 * invalidate any other reasons why the executable file might be covered by
 * the GNU General Public License.
 */

package cc.arduino.contributions.packages;

import cc.arduino.Constants;
import cc.arduino.contributions.DownloadableContribution;
import cc.arduino.contributions.DownloadableContributionBuiltInAtTheBottomComparator;
import cc.arduino.contributions.SignatureVerificationFailedException;
import cc.arduino.contributions.SignatureVerifier;
import cc.arduino.contributions.filters.BuiltInPredicate;
import cc.arduino.contributions.filters.InstalledPredicate;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.mrbean.MrBeanModule;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimaps;
import org.apache.commons.compress.utils.IOUtils;
import processing.app.I18n;
import processing.app.Platform;
import processing.app.PreferencesData;
import processing.app.debug.TargetPackage;
import processing.app.debug.TargetPlatform;
import processing.app.debug.TargetPlatformException;
import processing.app.helpers.FileUtils;
import processing.app.helpers.PreferencesMap;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

import static processing.app.I18n._;
import static processing.app.helpers.filefilters.OnlyDirs.ONLY_DIRS;

public class ContributionsIndexer {

  private final File packagesFolder;
  private final File stagingFolder;
  private final File preferencesFolder;
  private final Platform platform;
  private final SignatureVerifier signatureVerifier;
  private ContributionsIndex index;

  public ContributionsIndexer(File preferencesFolder, Platform platform, SignatureVerifier signatureVerifier) {
    this.preferencesFolder = preferencesFolder;
    this.platform = platform;
    this.signatureVerifier = signatureVerifier;
    packagesFolder = new File(preferencesFolder, "packages");
    stagingFolder = new File(preferencesFolder, "staging" + File.separator + "packages");
  }

  public void parseIndex() throws Exception {
    File defaultIndexFile = getIndexFile(Constants.DEFAULT_INDEX_FILE_NAME);
    if (!signatureVerifier.isSigned(defaultIndexFile)) {
      throw new SignatureVerificationFailedException(Constants.DEFAULT_INDEX_FILE_NAME);
    }
    index = parseIndex(defaultIndexFile);
    index.setTrusted();

    File[] indexFiles = preferencesFolder.listFiles(new TestPackageIndexFilenameFilter(new PackageIndexFilenameFilter(Constants.DEFAULT_INDEX_FILE_NAME)));

    for (File indexFile : indexFiles) {
      ContributionsIndex contributionsIndex = parseIndex(indexFile);
      mergeContributions(contributionsIndex, indexFile);
    }

    List<ContributedPackage> packages = index.getPackages();
    Collection<ContributedPackage> packagesWithTools = Collections2.filter(packages, input -> input.getTools() != null);

    for (ContributedPackage pack : packages) {
      for (ContributedPlatform platform : pack.getPlatforms()) {
        // Set a reference to parent packages
        platform.setParentPackage(pack);

        // Resolve tools dependencies (works also as a check for file integrity)
        platform.resolveToolsDependencies(packagesWithTools);
      }
    }

    index.fillCategories();
  }

  private void mergeContributions(ContributionsIndex contributionsIndex, File indexFile) {
    boolean signed = signatureVerifier.isSigned(indexFile);
    boolean trustall = PreferencesData.getBoolean(Constants.PREF_CONTRIBUTIONS_TRUST_ALL);

    for (ContributedPackage contributedPackage : contributionsIndex.getPackages()) {
      contributedPackage.setTrusted(signed || trustall);
      if (!contributedPackage.isTrusted()) {
        for (ContributedPlatform contributedPlatform : contributedPackage.getPlatforms()) {
          contributedPlatform.setCategory("Contributed");
        }
      }

      ContributedPackage targetPackage = index.getPackage(contributedPackage.getName());

      if (targetPackage == null) {
        index.getPackages().add(contributedPackage);
      } else {
        if (contributedPackage.isTrusted() || !isPackageNameProtected(contributedPackage)) {
          if (isPackageNameProtected(contributedPackage) && trustall) {
            System.err.println(I18n.format(_("Warning: forced trusting untrusted contributions")));
          }
          List<ContributedPlatform> platforms = contributedPackage.getPlatforms();
          if (platforms == null) {
            platforms = new LinkedList<>();
          }
          for (ContributedPlatform contributedPlatform : platforms) {
            ContributedPlatform platform = targetPackage.findPlatform(contributedPlatform.getArchitecture(), contributedPlatform.getVersion());
            if (platform != null) {
              targetPackage.getPlatforms().remove(platform);
            }
            targetPackage.getPlatforms().add(contributedPlatform);
          }
          List<ContributedTool> tools = contributedPackage.getTools();
          if (tools == null) {
            tools = new LinkedList<>();
          }
          for (ContributedTool contributedTool : tools) {
            ContributedTool tool = targetPackage.findTool(contributedTool.getName(), contributedTool.getVersion());
            if (tool != null) {
              targetPackage.getTools().remove(tool);
            }
            targetPackage.getTools().add(contributedTool);
          }
        }
      }
    }
  }

  private boolean isPackageNameProtected(ContributedPackage contributedPackage) {
    return Constants.PROTECTED_PACKAGE_NAMES.contains(contributedPackage.getName());
  }

  private ContributionsIndex parseIndex(File indexFile) throws IOException {
    InputStream inputStream = null;
    try {
      inputStream = new FileInputStream(indexFile);
      ObjectMapper mapper = new ObjectMapper();
      mapper.registerModule(new MrBeanModule());
      mapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
      mapper.configure(DeserializationFeature.EAGER_DESERIALIZER_FETCH, true);
      mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
      return mapper.readValue(inputStream, ContributionsIndex.class);
    } finally {
      IOUtils.closeQuietly(inputStream);
    }
  }

  public void syncWithFilesystem(File hardwareFolder) throws IOException {
    syncBuiltInHardwareFolder(hardwareFolder);

    syncLocalPackagesFolder();
  }

  private void syncBuiltInHardwareFolder(File hardwareFolder) throws IOException {
    if (index == null) {
      return;
    }
    for (File folder : hardwareFolder.listFiles(ONLY_DIRS)) {
      ContributedPackage pack = index.findPackage(folder.getName());
      if (pack != null) {
        syncBuiltInPackageWithFilesystem(pack, folder);

        File toolsFolder = new File(hardwareFolder, "tools");
        if (toolsFolder.isDirectory()) {
          for (File toolFolder : toolsFolder.listFiles(ONLY_DIRS)) {
            File builtInToolsMetadata = new File(toolFolder, "builtin_tools_versions.txt");
            if (builtInToolsMetadata.isFile()) {
              PreferencesMap toolsMetadata = new PreferencesMap(builtInToolsMetadata).subTree(pack.getName());
              for (Map.Entry<String, String> toolMetadata : toolsMetadata.entrySet()) {
                syncToolWithFilesystem(pack, toolFolder, toolMetadata.getKey(), toolMetadata.getValue());
              }
            }
          }
        }
      }
    }
  }

  private void syncBuiltInPackageWithFilesystem(ContributedPackage pack, File hardwareFolder) throws IOException {
    // Scan all hardware folders and mark as installed all the tools found.
    for (File platformFolder : hardwareFolder.listFiles(ONLY_DIRS)) {
      File platformTxt = new File(platformFolder, "platform.txt");
      String version = new PreferencesMap(platformTxt).get("version");
      ContributedPlatform platform = syncHardwareWithFilesystem(pack, platformFolder, platformFolder.getName(), version);
      if (platform != null) {
        platform.setReadOnly(true);
      }
    }
  }

  private void syncLocalPackagesFolder() {
    if (!packagesFolder.isDirectory()) {
      return;
    }

    if (index == null) {
      return;
    }

    // Scan all hardware folders and mark as installed all the
    // platforms found.
    for (File folder : packagesFolder.listFiles(ONLY_DIRS)) {
      ContributedPackage pack = index.findPackage(folder.getName());
      if (pack != null) {
        syncPackageWithFilesystem(pack, folder);
      }
    }
  }

  private void syncPackageWithFilesystem(ContributedPackage pack, File root) {
    // Scan all hardware folders and mark as installed all the tools found.
    File hardwareFolder = new File(root, "hardware");
    if (hardwareFolder.isDirectory()) {
      for (File platformFolder : hardwareFolder.listFiles(ONLY_DIRS)) {
        for (File versionFolder : platformFolder.listFiles(ONLY_DIRS)) {
          syncHardwareWithFilesystem(pack, versionFolder, platformFolder.getName(), versionFolder.getName());
        }
      }
    }

    // Scan all tools folders and mark as installed all the tools found.
    File toolsFolder = new File(root, "tools");
    if (toolsFolder.isDirectory()) {
      for (File toolFolder : toolsFolder.listFiles(ONLY_DIRS)) {
        for (File versionFolder : toolFolder.listFiles(ONLY_DIRS)) {
          syncToolWithFilesystem(pack, versionFolder, toolFolder.getName(), versionFolder.getName());
        }
      }
    }
  }

  private void syncToolWithFilesystem(ContributedPackage pack, File installationFolder, String toolName, String version) {
    ContributedTool tool = pack.findTool(toolName, version);
    if (tool == null) {
      return;
    }
    DownloadableContribution contrib = tool.getDownloadableContribution(platform);
    if (contrib == null) {
      System.err.println(tool + " seems to have no downloadable contributions for your operating system, but it is installed in\n" + installationFolder);
      return;
    }
    contrib.setInstalled(true);
    contrib.setInstalledFolder(installationFolder);
  }

  private ContributedPlatform syncHardwareWithFilesystem(ContributedPackage pack, File installationFolder, String architecture, String version) {
    ContributedPlatform platform = pack.findPlatform(architecture, version);
    if (platform != null) {
      platform.setInstalled(true);
      platform.setReadOnly(false);
      platform.setInstalledFolder(installationFolder);
    }
    return platform;
  }

  @Override
  public String toString() {
    return index.toString();
  }

  public List<TargetPackage> createTargetPackages() {
    List<TargetPackage> packages = new ArrayList<>();

    if (index == null) {
      return packages;
    }

    for (ContributedPackage aPackage : index.getPackages()) {
      ContributedTargetPackage targetPackage = new ContributedTargetPackage(aPackage.getName());

      List<ContributedPlatform> platforms = aPackage.getPlatforms().stream().filter(new InstalledPredicate()).collect(Collectors.toList());
      Collections.sort(platforms, new DownloadableContributionBuiltInAtTheBottomComparator());

      for (ContributedPlatform platform : platforms) {
        String arch = platform.getArchitecture();
        File folder = platform.getInstalledFolder();

        try {
          TargetPlatform targetPlatform = new ContributedTargetPlatform(arch, folder, targetPackage, index);
          if (!targetPackage.hasPlatform(targetPlatform)) {
            targetPackage.addPlatform(targetPlatform);
          }
        } catch (TargetPlatformException e) {
          System.err.println(e.getMessage());
        }
      }

      if (targetPackage.hasPlatforms()) {
        packages.add(targetPackage);
      }
    }

    Collections.sort(packages, (package1, package2) -> {
      assert package1.getId() != null && package2.getId() != null;
      return package1.getId().toLowerCase().compareTo(package2.getId().toLowerCase());
    });

    return packages;
  }

  public boolean isContributedToolUsed(ContributedTool tool) {
    for (ContributedPackage pack : index.getPackages()) {
      for (ContributedPlatform platform : pack.getPlatforms()) {
        if (!platform.isInstalled())
          continue;
        for (ContributedTool requiredTool : platform.getResolvedTools()) {
          if (requiredTool.equals(tool))
            return true;
        }
      }
    }
    return false;
  }

  public Set<ContributedTool> getInstalledTools() {
    Set<ContributedTool> tools = new HashSet<>();
    if (index == null) {
      return tools;
    }
    for (ContributedPackage pack : index.getPackages()) {
      Collection<ContributedPlatform> platforms = pack.getPlatforms().stream().filter(new InstalledPredicate()).collect(Collectors.toList());
      ImmutableListMultimap<String, ContributedPlatform> platformsByName = Multimaps.index(platforms, ContributedPlatform::getName);

      for (Map.Entry<String, Collection<ContributedPlatform>> entry : platformsByName.asMap().entrySet()) {
        Collection<ContributedPlatform> platformsWithName = entry.getValue();
        if (platformsWithName.size() > 1) {
          platformsWithName = platformsWithName.stream().filter(new BuiltInPredicate().negate()).collect(Collectors.toList());
        }
        for (ContributedPlatform platform : platformsWithName) {
          tools.addAll(platform.getResolvedTools());
        }
      }
    }
    return tools;
  }

  public ContributionsIndex getIndex() {
    return index;
  }

  public File getPackagesFolder() {
    return packagesFolder;
  }

  public File getStagingFolder() {
    return stagingFolder;
  }

  public File getIndexFile(String name) {
    return new File(preferencesFolder, name);
  }

  public List<ContributedPackage> getPackages() {
    if (index == null) {
      return new LinkedList<>();
    }
    return index.getPackages();
  }

  public List<String> getCategories() {
    if (index == null) {
      return new LinkedList<>();
    }
    return index.getCategories();
  }

  public ContributedPlatform getInstalled(String packageName, String platformArch) {
    if (index == null) {
      return null;
    }
    return index.getInstalledPlatform(packageName, platformArch);
  }

  private List<ContributedPlatform> getInstalledPlatforms() {
    if (index == null) {
      return new LinkedList<>();
    }
    return index.getInstalledPlatforms();
  }

  public boolean isFolderInsidePlatform(final File folder) {
    return getPlatformByFolder(folder) != null;
  }

  public ContributedPlatform getPlatformByFolder(final File folder) {
    com.google.common.base.Optional<ContributedPlatform> platformOptional = Iterables.tryFind(getInstalledPlatforms(), contributedPlatform -> {
      assert contributedPlatform.getInstalledFolder() != null;
      return FileUtils.isSubDirectory(contributedPlatform.getInstalledFolder(), folder);
    });

    return platformOptional.orNull();
  }
}
