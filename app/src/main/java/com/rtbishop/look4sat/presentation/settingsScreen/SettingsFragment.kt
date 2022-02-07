/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2021 Arty Bishop (bishop.arty@gmail.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.presentation.settingsScreen

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.asLiveData
import androidx.navigation.fragment.findNavController
import com.rtbishop.look4sat.BuildConfig
import com.rtbishop.look4sat.R
import com.rtbishop.look4sat.databinding.FragmentSettingsBinding
import com.rtbishop.look4sat.domain.model.DataState
import com.rtbishop.look4sat.domain.predict.GeoPos
import com.rtbishop.look4sat.presentation.*
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private val viewModel: SettingsViewModel by viewModels()
    private val locationFine = Manifest.permission.ACCESS_FINE_LOCATION
    private val locationCoarse = Manifest.permission.ACCESS_COARSE_LOCATION
    private val locationContract = ActivityResultContracts.RequestMultiplePermissions()
    private val locationRequest = registerForActivityResult(locationContract) { permissions ->
        when {
            permissions[locationFine] == true -> viewModel.setPositionFromGps()
            permissions[locationCoarse] == true -> viewModel.setPositionFromNet()
            else -> showToast(getString(R.string.pref_pos_gps_error))
        }
    }
    private val contentContract = ActivityResultContracts.GetContent()
    private val contentRequest = registerForActivityResult(contentContract) { uri ->
        uri?.let { viewModel.updateDataFromFile(uri.toString()) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val settingsBinding = FragmentSettingsBinding.bind(view)
        settingsBinding.prefsBack.setOnClickListener { findNavController().navigateUp() }
        setupAboutCard(settingsBinding)
        setupDataCard(settingsBinding)
        setupLocationCard(settingsBinding)
        setupTrackingCard(settingsBinding)
        setupOtherCard(settingsBinding)
        setupWarrantyCard(settingsBinding)
        viewModel.stationPosition.asLiveData().observe(viewLifecycleOwner) { stationPos ->
            stationPos?.let { handleStationPosition(stationPos, settingsBinding) }
        }
        viewModel.getUpdateState().asLiveData().observe(viewLifecycleOwner) { updateState ->
            updateState?.let { handleSatState(updateState, settingsBinding) }
        }
    }

    private fun setupAboutCard(binding: FragmentSettingsBinding) {
        binding.prefsInfo.aboutVersion.text =
            String.format(getString(R.string.about_version), BuildConfig.VERSION_NAME)
        binding.prefsInfo.aboutBtnGithub.setOnClickListener {
            gotoUrl("https://github.com/rt-bishop/Look4Sat/")
        }
        binding.prefsInfo.aboutBtnDonate.setOnClickListener {
            gotoUrl("https://www.buymeacoffee.com/rtbishop")
        }
        binding.prefsInfo.aboutBtnFdroid.setOnClickListener {
            gotoUrl("https://f-droid.org/en/packages/com.rtbishop.look4sat/")
        }
    }

    private fun setupLocationCard(binding: FragmentSettingsBinding) {
        setPositionText(viewModel.getStationPosition(), binding)
        binding.prefsLocation.locationBtnGps.setOnClickListener {
            locationRequest.launch(arrayOf(locationFine, locationCoarse))
        }
        binding.prefsLocation.locationBtnQth.setOnClickListener {
            val editText = EditText(requireActivity())
            AlertDialog.Builder(requireContext())
                .setTitle("Title")
                .setEditText(editText)
                .setPositiveButton("OK") { _, _ ->
                    val editTextInput = editText.text.toString()
                    viewModel.setPositionFromQth(editTextInput)
                }
                .setNeutralButton("Cancel", null)
                .create()
                .show()
        }
    }

    private fun setupDataCard(binding: FragmentSettingsBinding) {
        binding.prefsData.updateBtnFile.setOnClickListener { contentRequest.launch("*/*") }
        binding.prefsData.updateBtnWeb.setOnClickListener {
            findNavController().navigateSafe(R.id.action_prefs_to_sources)
        }
        getNavResult<List<String>>(R.id.nav_settings, "sources") { sources ->
            viewModel.updateDataFromWeb(sources)
        }
    }

    private fun setupTrackingCard(binding: FragmentSettingsBinding) {
        binding.prefsTracking.trackingSwitch.apply {
            isChecked = viewModel.getRotatorEnabled()
            binding.prefsTracking.trackingIp.isEnabled = isChecked
            binding.prefsTracking.trackingIpEdit.setText(viewModel.getRotatorServer())
            binding.prefsTracking.trackingPort.isEnabled = isChecked
            binding.prefsTracking.trackingPortEdit.setText(viewModel.getRotatorPort())
            setOnCheckedChangeListener { _, isChecked ->
                viewModel.setRotatorEnabled(isChecked)
                binding.prefsTracking.trackingIp.isEnabled = isChecked
                binding.prefsTracking.trackingPort.isEnabled = isChecked
            }
        }
        binding.prefsTracking.trackingIpEdit.doOnTextChanged { text, _, _, _ ->
            if (text.toString().isValidIPv4()) viewModel.setRotatorServer(text.toString())
        }
        binding.prefsTracking.trackingPortEdit.doOnTextChanged { text, _, _, _ ->
            if (text.toString().isValidPort()) viewModel.setRotatorPort(text.toString())
        }
    }

    private fun setupOtherCard(binding: FragmentSettingsBinding) {
        binding.prefsOther.otherSwitchUtc.apply {
            isChecked = viewModel.getUseUTC()
            setOnCheckedChangeListener { _, isChecked -> viewModel.setUseUTC(isChecked) }
        }
        binding.prefsOther.otherSwitchSweep.apply {
            isChecked = viewModel.getShowSweep()
            setOnCheckedChangeListener { _, isChecked -> viewModel.setShowSweep(isChecked) }
        }
        binding.prefsOther.otherSwitchSensors.apply {
            isChecked = viewModel.getUseCompass()
            setOnCheckedChangeListener { _, isChecked -> viewModel.setUseCompass(isChecked) }
        }
    }

    private fun setupWarrantyCard(binding: FragmentSettingsBinding) {
        binding.prefsWarranty.warrantyThanks.movementMethod = LinkMovementMethod.getInstance()
        binding.prefsWarranty.warrantyLicense.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun setPositionText(geoPos: GeoPos, binding: FragmentSettingsBinding) {
        val latFormat = getString(R.string.location_lat)
        val lonFormat = getString(R.string.location_lon)
        binding.prefsLocation.locationLat.text = String.format(latFormat, geoPos.latitude)
        binding.prefsLocation.locationLon.text = String.format(lonFormat, geoPos.longitude)
    }

    private fun handleStationPosition(pos: DataState<GeoPos>, binding: FragmentSettingsBinding) {
        when (pos) {
            is DataState.Success -> {
                setPositionText(pos.data, binding)
                binding.prefsLocation.locationProgress.isIndeterminate = false
                viewModel.setPositionHandled()
                showToast(getString(R.string.pref_pos_success))
            }
            is DataState.Error -> {
                binding.prefsLocation.locationProgress.isIndeterminate = false
                viewModel.setPositionHandled()
                showToast(pos.message.toString())
            }
            DataState.Loading -> {
                binding.prefsLocation.locationProgress.isIndeterminate = true
            }
            DataState.Handled -> {}
        }
    }

    private fun handleSatState(state: DataState<Long>, binding: FragmentSettingsBinding) {
        when (state) {
            is DataState.Success -> {
                binding.prefsData.updateProgress.isIndeterminate = false
                viewModel.setUpdateHandled()
                showToast("Data updated successfully")
            }
            is DataState.Error -> {
                binding.prefsData.updateProgress.isIndeterminate = false
                viewModel.setUpdateHandled()
                showToast(state.message.toString())
            }
            is DataState.Loading -> {
                binding.prefsData.updateProgress.isIndeterminate = true
            }
            is DataState.Handled -> {}
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun gotoUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
