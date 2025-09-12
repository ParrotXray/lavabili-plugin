#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import time
from rich.console import Console
from rich.table import Table
from selenium import webdriver
from selenium.webdriver.common.by import By
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC
from selenium.webdriver.chrome.options import Options
from selenium.common.exceptions import TimeoutException, NoSuchElementException

class BilibiliCookieExtractor:
    def __init__(self):
        self.driver: webdriver.Chrome = None
        self.console: Console = Console(log_time=True, soft_wrap=True, log_time_format="[%H:%M:%S.%f]")
        self.setup_driver()
        
    def is_browser_alive(self):
            try:
                self.driver.current_window_handle
                return True
            except Exception:
                return False        

    def setup_driver(self):
        chrome_options = Options()
        
        chrome_options.add_argument("--incognito")
        chrome_options.add_argument('--disable-logging')
        chrome_options.add_argument('--disable-gpu-logging')
        chrome_options.add_argument('--disable-extensions-logging')
        chrome_options.add_argument('--disable-web-security')
        chrome_options.add_argument('--disable-features=TranslateUI')
        chrome_options.add_argument('--disable-background-networking')
        chrome_options.add_argument('--disable-background-timer-throttling')
        chrome_options.add_argument('--disable-backgrounding-occluded-windows')
        chrome_options.add_argument('--disable-renderer-backgrounding')
        chrome_options.add_argument('--log-level=3')
        
        chrome_options.add_argument('--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36')
        
        try:
            self.driver = webdriver.Chrome(options=chrome_options)
            self.console.log("Chrome browser started successfully")
        except Exception as e:
            self.console.log(f"Failed to start browser: {e}")
            self.console.log("Please ensure ChromeDriver is installed and added to PATH")
            raise
    
    def login_bilibili(self):
        try:
            self.console.log("Accessing Bilibili...")
            self.driver.get("https://www.bilibili.com")
            time.sleep(3)

            if not self.is_browser_alive():
                self.console.log("Browser has been closed")
                return False
            
            try:
                login_button = WebDriverWait(self.driver, 10).until(
                    EC.element_to_be_clickable((By.CLASS_NAME, "header-login-entry"))
                )
                login_button.click()
                self.console.log("Clicked login button")
            except TimeoutException:
                if self.check_login_status():
                    self.console.log("Already logged in detected")
                    return True
                else:
                    self.console.log("Login button not found")
                    return False
            
            time.sleep(2)

            return self.wait_for_login()

                
        except Exception as e:
            self.console.log(f"Error during login process: {e}")
            return False

    def wait_for_login(self, timeout: int = 300):
        self.console.log(f"Waiting for login completion (max {timeout} seconds)...")
        
        start_time = time.time()
        while time.time() - start_time < timeout:
            if not self.is_browser_alive():
                self.console.log("Browser has been closed")
                return False

            if self.check_login_status():
                self.console.log("Login successful!")
                return True
            
            time.sleep(2)
            remaining = int(timeout - (time.time() - start_time))
            if remaining % 10 == 0:
                self.console.log(f"Time remaining: {remaining} seconds...")
        
        self.console.log("Login timeout")
        return False
    
    def check_login_status(self):
        try:
            if not self.is_browser_alive():
                return False
            
            self.driver.find_element(By.CLASS_NAME, "header-avatar-wrap")
            return True
        except NoSuchElementException:
            try:
                self.driver.find_element(By.CLASS_NAME, "user-con")
                return True
            except NoSuchElementException:
                return False
    
    def extract_cookies_and_storage(self):
        if not self.is_browser_alive():
            self.console.log("Browser connection lost, cannot extract cookies")
            return {}
        
        self.console.log("Extracting cookies...")
        
        cookies = self.driver.get_cookies()
        cookie_dict = {cookie['name']: cookie['value'] for cookie in cookies}
        
        required_cookies = ['SESSDATA', 'bili_jct', 'DedeUserID', 'buvid3', 'buvid4']
        extracted = {}
        
        for cookie_name in required_cookies:
            value = cookie_dict.get(cookie_name, '')
            extracted[cookie_name] = value
            if value:
                self.console.log(f"Found {cookie_name}: {value[:10]}...")
            else:
                self.console.log(f"Missing {cookie_name}")

        self.console.log("Extracting Local Storage...")

        try:
            ac_time_value = self.driver.execute_script("return window.localStorage.ac_time_value;")
            extracted['ac_time_value'] = ac_time_value or ''
            
            if ac_time_value:
                self.console.log(f"Found ac_time_value: {ac_time_value}")
            else:
                self.console.log("Missing ac_time_value")
                        
        except Exception as e:
            self.console.log(f"Error extracting Local Storage: {e}")

        return extracted
    
    def run(self):
        try:
            self.console.log("Starting Bilibili Cookie and Local Storage extraction...")
            
            # Login
            if self.login_bilibili():
                # Wait for page to fully load
                time.sleep(3)
                
                # Extract cookies and local storage
                data = self.extract_cookies_and_storage()
                
                # Validate required data
                if data.get('SESSDATA') and data.get('bili_jct'):
                    self.console.log("Successfully obtained all required authentication information!")
                    
                    # Validate ac_time_value
                    if data.get('ac_time_value'):
                        self.console.log("Successfully obtained ac_time_value")
                    else:
                        self.console.log("ac_time_value not found, but does not affect basic functionality")


                    self.console.log("Security Warning: Never share your cookies - they're equivalent to your login credentials!")
                    self.console.log(f"SESSDATA: {data.get('SESSDATA')}")
                    self.console.log(f"bili_jct: {data.get('bili_jct')}")
                    self.console.log(f"DedeUserID: {data.get('DedeUserID')}")
                    self.console.log(f"buvid3: {data.get('buvid3')}")
                    self.console.log(f"buvid4: {data.get('buvid4')}")
                    self.console.log(f"ac_time_value: {data.get('ac_time_value')}")
                    
                    return True
                else:
                    self.console.log("Missing required cookies, please check login status")
                    return False
            else:
                self.console.log("Login failed")
                return False
                
        except KeyboardInterrupt:
            self.console.log("Operation interrupted by user")
        except Exception as e:
            self.console.log(f"Runtime error: {e}")
        finally:
            self.cleanup()
    
    def cleanup(self):
        if self.driver:
            try:
                # Check if browser is still alive before cleanup
                if self.is_browser_alive():
                    self.console.log("Closing browser...")
                    self.driver.quit()
                else:
                    self.console.log("Browser already closed, cleaning session...")
            except Exception:
                self.console.log("Cleaning browser session...")
            finally:
                self.driver = None

if __name__ == "__main__":
    extractor = BilibiliCookieExtractor()
    extractor.run()